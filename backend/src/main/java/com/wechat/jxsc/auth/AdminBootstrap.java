package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.OperationAuditService;

import jakarta.persistence.*;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Component
public class AdminBootstrap implements ApplicationRunner {
    @PersistenceContext private EntityManager em;
    private final Environment environment;
    private final OperationAuditService audit;

    public AdminBootstrap(Environment environment, OperationAuditService audit) {
        this.environment = environment;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = environment.getProperty("ADMIN_BOOTSTRAP_PASSWORD");
        if (password == null || password.isBlank()) return;
        String login = environment.getProperty("ADMIN_BOOTSTRAP_LOGIN");
        if (login == null || !login.matches("[A-Za-z0-9_.-]{3,100}"))
            throw new IllegalStateException("Explicit valid ADMIN_BOOTSTRAP_LOGIN is required");
        // Lock the seeded role to serialize first-account initialization across instances.
        var role =
                em.createQuery(
                                "select r from AdminRole r where r.code='SUPER_ADMIN'",
                                AdminRole.class)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getSingleResult();
        if (em.createQuery("select count(a) from AdminUser a", Long.class).getSingleResult() > 0)
            return;
        AdminAuthService.validateNewPassword(password);
        var admin = new AdminUser();
        admin.loginName = login;
        admin.passwordHash = new BCryptPasswordEncoder(12).encode(password);
        admin.roles.add(role);
        em.persist(admin);
        audit.append(
                new CurrentActor(CurrentActor.Type.ADMIN, admin.getId(), null, Set.of(), true),
                "ADMIN_BOOTSTRAPPED",
                "ADMIN_USER",
                admin.getId().toString(),
                null,
                Map.of("passwordChangeRequired", true),
                "Deployment bootstrap",
                "bootstrap");
    }
}
