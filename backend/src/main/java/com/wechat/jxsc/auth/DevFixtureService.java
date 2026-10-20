package com.wechat.jxsc.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Set;

@Service
@Conditional(DevIdentityCondition.class)
public class DevFixtureService {
    @org.springframework.beans.factory.annotation.Autowired private AccountLifecycle lifecycle;
    public static final Set<String> FIXTURES =
            Set.of(
                    "buyer",
                    "buyer-active-member",
                    "buyer-expired-member",
                    "distributor-a",
                    "distributor-b");
    @PersistenceContext private EntityManager em;
    private final SessionService sessions;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final com.wechat.jxsc.distribution.DistributorQualification qualification;

    public DevFixtureService(
            SessionService sessions,
            Clock clock,
            PlatformTransactionManager transactions,
            com.wechat.jxsc.distribution.DistributorQualification qualification) {
        this.sessions = sessions;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.qualification = qualification;
    }

    public AuthDtos.CustomerLogin login(String fixture, String device) {
        if (!FIXTURES.contains(fixture)) throw CurrentActor.unauthorized();
        // A concurrent first login can lose the unique (app_id,openid) race. Roll back
        // its newly allocated user and retry in a fresh transaction to use the winner.
        try {
            return transaction.execute(status -> loginInTransaction(fixture, device));
        } catch (org.springframework.dao.DataIntegrityViolationException
                | jakarta.persistence.PersistenceException collision) {
            if (!isIdentityCollision(collision)) throw collision;
            return transaction.execute(status -> loginInTransaction(fixture, device));
        }
    }

    private boolean isIdentityCollision(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation)
                return violation.getConstraintName() != null
                        && violation.getConstraintName().contains("uk_identity_app_openid");
        }
        return false;
    }

    private AuthDtos.CustomerLogin loginInTransaction(String fixture, String device) {
        var identities =
                em.createQuery(
                                "select i from WechatIdentity i join fetch i.user where"
                                        + " i.appId='DEV' and i.openid=:fixture",
                                WechatIdentity.class)
                        .setParameter("fixture", fixture)
                        .getResultList();
        WechatIdentity identity;
        if (identities.isEmpty()) {
            var user = new UserAccount("开发数据 · " + fixture);
            em.persist(user);
            identity = new WechatIdentity();
            identity.user = user;
            identity.appId = "DEV";
            identity.openid = fixture;
            identity.lastLoginAt = clock.instant();
            em.persist(identity);
            em.flush();
        } else {
            identity = identities.getFirst();
            lifecycle.requireActive(identity.user.getId());
            em.refresh(identity.user, LockModeType.PESSIMISTIC_WRITE);
            // Refresh under a row lock: concurrent logins must not update a stale
            // identity version after waiting for another login to finish.
            em.refresh(identity, LockModeType.PESSIMISTIC_WRITE);
        }
        if (!"ACTIVE".equals(identity.user.status)) throw CurrentActor.unauthorized();
        lifecycle.requireActive(identity.user.getId());
        identity.lastLoginAt = clock.instant();
        var issued = sessions.issue(CurrentActor.Type.CUSTOMER, identity.user.getId(), device);
        return new AuthDtos.CustomerLogin(
                issued.token(),
                issued.expiresAt(),
                new AuthDtos.UserView(
                        identity.user.getId().toString(),
                        identity.user.nickname,
                        qualification.enabled(identity.user.getId())));
    }
}
