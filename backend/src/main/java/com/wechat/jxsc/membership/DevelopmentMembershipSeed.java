package com.wechat.jxsc.membership;

import com.wechat.jxsc.common.content.*;

import org.springframework.boot.*;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;

@Component
@Conditional(DevelopmentSeedCondition.class)
public class DevelopmentMembershipSeed implements ApplicationRunner {
    private final DevelopmentContentSeed content;
    private final EntitlementLedger ledger;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DevelopmentMembershipSeed(
            DevelopmentContentSeed content,
            EntitlementLedger ledger,
            JdbcTemplate db,
            PlatformTransactionManager manager,
            Clock clock) {
        this.content = content;
        this.ledger = ledger;
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        // Content/identity setup finishes before the separate entitlement transaction.
        content.initialize();
        seed(
                "buyer-active-member",
                "DEV-MEMBER-ACTIVE-V1",
                clock.instant().minus(Duration.ofDays(1)));
        seed(
                "buyer-expired-member",
                "DEV-MEMBER-EXPIRED-V1",
                clock.instant().atZone(ZoneId.of("Asia/Shanghai")).minusMonths(12).toInstant());
    }

    private void seed(String fixture, String source, Instant paid) {
        tx.executeWithoutResult(
                s -> {
                    long user =
                            db.queryForObject(
                                    "select user_id from user_wechat_identity where app_id='DEV'"
                                            + " and openid=?",
                                    Long.class,
                                    fixture);
                    db.queryForObject(
                            "select id from user_account where id=? for update", Long.class, user);
                    ledger.ensureSubject(user);
                    ledger.grant(
                            user,
                            null,
                            source,
                            null,
                            paid.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                            null,
                            6);
                });
    }
}
