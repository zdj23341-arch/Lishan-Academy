package com.wechat.jxsc.auth;

import com.wechat.jxsc.auth.wechat.WechatLoginClient;
import com.wechat.jxsc.payment.wechat.WechatSettings;

import jakarta.persistence.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.util.Set;

@Service
public class WechatLoginService {
    @org.springframework.beans.factory.annotation.Autowired private AccountLifecycle lifecycle;
    @PersistenceContext private EntityManager em;
    private final SessionService sessions;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final WechatLoginClient client;
    private final Environment env;

    public WechatLoginService(
            SessionService sessions,
            Clock clock,
            PlatformTransactionManager tx,
            WechatSettings settings,
            com.fasterxml.jackson.databind.ObjectMapper json,
            Environment env) {
        this.sessions = sessions;
        this.clock = clock;
        this.tx = new TransactionTemplate(tx);
        this.env = env;
        client =
                new WechatLoginClient(
                        URI.create("https://api.weixin.qq.com"),
                        settings.appId(),
                        settings.get("login.app-secret"),
                        json);
    }

    public AuthDtos.CustomerLogin login(String code, String device) {
        if (!"REAL".equals(env.getProperty("jxsc.providers.identity", "REAL")))
            throw com.wechat.jxsc.payment.wechat.WechatProtocol.unavailable(
                    "WECHAT_LOGIN_NOT_CONFIGURED");
        return provision(client.exchange(code), device);
    }

    public AuthDtos.CustomerLogin provision(WechatLoginClient.Identity identity, String device) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> create(identity, device));
            } catch (org.springframework.dao.DataIntegrityViolationException
                    | jakarta.persistence.PersistenceException e) {
                boolean collision = false;
                for (Throwable c = e; c != null; c = c.getCause())
                    if (c instanceof org.hibernate.exception.ConstraintViolationException v
                            && v.getConstraintName() != null
                            && v.getConstraintName().contains("uk_identity_app_openid"))
                        collision = true;
                if (!collision || attempt >= 2) throw e;
            }
        }
    }

    private AuthDtos.CustomerLogin create(WechatLoginClient.Identity result, String device) {
        // Serialize first provisioning before the identity SELECT; collision diagnostics must never
        // expose openid.
        em.createNativeQuery(
                        "insert into wechat_identity_guard(identity_hash) values(:h) on duplicate"
                                + " key update identity_hash=identity_hash")
                .setParameter("h", TokenCodec.hash(result.appId() + "\u0000" + result.openid()))
                .executeUpdate();
        var rows =
                em.createQuery(
                                "select i from WechatIdentity i where i.appId=:a and i.openid=:o",
                                WechatIdentity.class)
                        .setParameter("a", result.appId())
                        .setParameter("o", result.openid())
                        .getResultList();
        WechatIdentity identity;
        if (rows.isEmpty()) {
            var user = new UserAccount("微信用户");
            em.persist(user);
            identity = new WechatIdentity();
            identity.user = user;
            identity.appId = result.appId();
            identity.openid = result.openid();
            identity.unionid = result.unionid();
            identity.lastLoginAt = clock.instant();
            em.persist(identity);
            em.flush();
        } else {
            identity = rows.getFirst();
            lifecycle.requireActive(identity.user.getId());
            em.refresh(identity.user, LockModeType.PESSIMISTIC_WRITE);
            em.refresh(identity, LockModeType.PESSIMISTIC_WRITE);
        }
        if (!"ACTIVE".equals(identity.user.status)) throw CurrentActor.unauthorized();
        lifecycle.requireActive(identity.user.getId());
        identity.lastLoginAt = clock.instant();
        em.createNativeQuery("insert ignore into entitlement_subject(user_id) values(:u)")
                .setParameter("u", identity.user.getId())
                .executeUpdate();
        em.createNativeQuery(
                        "insert ignore into distribution_identity(identity_id,user_id)"
                                + " values(:i,:u)")
                .setParameter("i", identity.getId())
                .setParameter("u", identity.user.getId())
                .executeUpdate();
        var issued = sessions.issue(CurrentActor.Type.CUSTOMER, identity.user.getId(), device);
        var actor =
                new CurrentActor(
                        CurrentActor.Type.CUSTOMER, identity.user.getId(), 0L, Set.of(), false);
        return new AuthDtos.CustomerLogin(
                issued.token(), issued.expiresAt(), sessions.customer(actor));
    }
}
