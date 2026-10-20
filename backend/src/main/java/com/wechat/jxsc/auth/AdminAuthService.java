package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.common.OperationAuditService;

import jakarta.persistence.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

@Service
public class AdminAuthService {
    @PersistenceContext private EntityManager em;
    private final SessionService sessions;
    private final OperationAuditService audit;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("dummy-" + TokenCodec.generate());

    public AdminAuthService(SessionService sessions, OperationAuditService audit, Clock clock) {
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthDtos.AdminLoginResult login(
            AuthDtos.AdminLogin request, String device, String requestId) {
        validatePasswordBytes(request.password());
        var admins =
                em.createQuery("select a from AdminUser a where a.loginName=:name", AdminUser.class)
                        .setParameter("name", request.canonicalLoginName())
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getResultList();
        var admin = admins.isEmpty() ? null : admins.getFirst();
        boolean valid =
                passwords.matches(
                        request.password(), admin == null ? dummyHash : admin.passwordHash);
        if (!valid || admin == null || !"ACTIVE".equals(admin.status))
            throw CurrentActor.unauthorized();
        admin.lastLoginAt = clock.instant();
        var issued = sessions.issue(CurrentActor.Type.ADMIN, admin.getId(), device);
        audit.append(
                new CurrentActor(
                        CurrentActor.Type.ADMIN,
                        admin.getId(),
                        null,
                        SessionService.permissions(admin),
                        admin.passwordChangeRequired),
                "ADMIN_LOGIN",
                "ADMIN_USER",
                admin.getId().toString(),
                null,
                null,
                "Authentication",
                requestId);
        return new AuthDtos.AdminLoginResult(
                issued.token(),
                issued.expiresAt(),
                SessionService.permissions(admin),
                admin.passwordChangeRequired);
    }

    @Transactional
    public void changePassword(
            CurrentActor actor, AuthDtos.PasswordChange request, String requestId) {
        actor.requireType(CurrentActor.Type.ADMIN);
        validatePasswordBytes(request.currentPassword());
        var admin = em.find(AdminUser.class, actor.id(), LockModeType.PESSIMISTIC_WRITE);
        if (!passwords.matches(request.currentPassword(), admin.passwordHash))
            throw CurrentActor.unauthorized();
        validateNewPassword(request.newPassword());
        if (passwords.matches(request.newPassword(), admin.passwordHash))
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "New password must differ");
        admin.passwordHash = passwords.encode(request.newPassword());
        admin.passwordChangeRequired = false;
        em.createQuery(
                        "update LoginSession s set s.revokedAt=:now where s.actorType=:type and"
                                + " s.actorId=:id and s.revokedAt is null")
                .setParameter("now", clock.instant())
                .setParameter("type", CurrentActor.Type.ADMIN)
                .setParameter("id", actor.id())
                .executeUpdate();
        audit.append(
                actor,
                "ADMIN_PASSWORD_CHANGED",
                "ADMIN_USER",
                actor.id().toString(),
                null,
                Map.of("passwordChangeRequired", false),
                "Password changed",
                requestId);
    }

    static void validateNewPassword(String password) {
        if (password == null
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.isBlank())
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Password must contain 12 characters and at most 72 UTF-8 bytes");
    }

    static void validatePasswordBytes(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Password exceeds 72 UTF-8 bytes");
    }

    @Transactional
    public void logout(CurrentActor actor, String requestId) {
        actor.requireType(CurrentActor.Type.ADMIN);
        sessions.revoke(actor);
        audit.append(
                actor,
                "ADMIN_LOGOUT",
                "ADMIN_USER",
                actor.id().toString(),
                null,
                null,
                "Session revoked",
                requestId);
    }
}
