package com.wechat.jxsc.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

@Service
public class SessionService {
    @PersistenceContext private EntityManager em;
    private final Clock clock;
    private final Duration customerTtl;
    private final Duration adminTtl;
    private final com.wechat.jxsc.distribution.DistributorQualification qualification;

    public SessionService(
            Clock clock,
            @Value("${jxsc.auth.customer-ttl:P7D}") Duration customerTtl,
            @Value("${jxsc.auth.admin-ttl:PT8H}") Duration adminTtl,
            com.wechat.jxsc.distribution.DistributorQualification qualification) {
        this.clock = clock;
        this.customerTtl = customerTtl;
        this.adminTtl = adminTtl;
        this.qualification = qualification;
        if (customerTtl.isNegative()
                || customerTtl.isZero()
                || adminTtl.isNegative()
                || adminTtl.isZero())
            throw new IllegalArgumentException("Session TTL must be positive");
    }

    record Issued(String token, Instant expiresAt) {}

    Issued issue(CurrentActor.Type type, Long id, String device) {
        String token = TokenCodec.generate();
        var session = new LoginSession();
        session.actorType = type;
        session.actorId = id;
        if (type == CurrentActor.Type.CUSTOMER) session.userId = id;
        else session.adminUserId = id;
        session.tokenHash = TokenCodec.hash(token);
        session.expiresAt =
                clock.instant().plus(type == CurrentActor.Type.CUSTOMER ? customerTtl : adminTtl);
        session.deviceHash = TokenCodec.hash(device == null ? "" : device);
        em.persist(session);
        return new Issued(token, session.expiresAt);
    }

    @Transactional(readOnly = true)
    public CurrentActor authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw CurrentActor.unauthorized();
        var sessions =
                em.createQuery(
                                "select s from LoginSession s where s.tokenHash=:hash",
                                LoginSession.class)
                        .setParameter("hash", TokenCodec.hash(token))
                        .getResultList();
        if (sessions.isEmpty()) throw CurrentActor.unauthorized();
        var s = sessions.getFirst();
        if (s.revokedAt != null || !s.expiresAt.isAfter(clock.instant()))
            throw CurrentActor.unauthorized();
        if (s.actorType == CurrentActor.Type.CUSTOMER) {
            var user = em.find(UserAccount.class, s.actorId);
            if (user == null || !"ACTIVE".equals(user.status)) throw CurrentActor.unauthorized();
            return new CurrentActor(s.actorType, s.actorId, s.getId(), Set.of(), false);
        }
        var admin = em.find(AdminUser.class, s.actorId);
        if (admin == null || !"ACTIVE".equals(admin.status)) throw CurrentActor.unauthorized();
        return new CurrentActor(
                s.actorType,
                s.actorId,
                s.getId(),
                permissions(admin),
                admin.passwordChangeRequired);
    }

    static Set<String> permissions(AdminUser admin) {
        if (admin.passwordChangeRequired) return Set.of();
        var permissions = new TreeSet<String>();
        admin.roles.forEach(role -> role.permissions.forEach(p -> permissions.add(p.code)));
        return permissions;
    }

    @Transactional
    public void revoke(CurrentActor actor) {
        var session = em.find(LoginSession.class, actor.sessionId());
        if (session != null && session.revokedAt == null) session.revokedAt = clock.instant();
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserView customer(CurrentActor actor) {
        actor.requireType(CurrentActor.Type.CUSTOMER);
        var user = em.find(UserAccount.class, actor.id());
        String nickname = user.nickname;
        if (nickname == null || nickname.isBlank()) nickname = "微信用户";
        return new AuthDtos.UserView(
                user.getId().toString(), nickname, qualification.enabled(user.getId()));
    }

    @Transactional(readOnly = true)
    public AuthDtos.AdminView admin(CurrentActor actor) {
        actor.requireType(CurrentActor.Type.ADMIN);
        var admin = em.find(AdminUser.class, actor.id());
        var roles = new TreeSet<String>();
        admin.roles.forEach(r -> roles.add(r.code));
        return new AuthDtos.AdminView(
                admin.getId().toString(),
                admin.loginName,
                roles,
                permissions(admin),
                admin.passwordChangeRequired);
    }
}
