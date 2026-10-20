package com.wechat.jxsc.payment.wechat;

import com.wechat.jxsc.common.tasks.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public class WechatStatementWorker implements TaskHandler {
    private final WechatStatements statements;
    private final WechatStatementReconciliation reconciliation;
    private final WechatSettings settings;
    private final TaskQueue tasks;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public WechatStatementWorker(
            WechatStatements statements,
            WechatStatementReconciliation reconciliation,
            WechatSettings settings,
            TaskQueue tasks,
            Clock clock,
            org.springframework.transaction.PlatformTransactionManager manager,
            org.springframework.jdbc.core.JdbcTemplate db) {
        this.statements = statements;
        this.reconciliation = reconciliation;
        this.settings = settings;
        this.tasks = tasks;
        this.clock = clock;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.db = db;
    }

    @Scheduled(cron = "0 5 10 * * *", zone = "Asia/Shanghai")
    public void daily() {
        if (settings.enabled("statements.enabled"))
            enqueue(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).minusDays(1));
    }

    public void enqueue(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        if (!date.isBefore(today) || date.isBefore(today.minusMonths(3)))
            throw WechatProtocol.failure("WECHAT_STATEMENT_DATE_INVALID");
        tx.executeWithoutResult(
                status -> {
                    for (String type : new String[] {"TRADE", "FUND_BASIC", "FUND_OPERATION"}) {
                        String key = date + ":" + type;
                        tasks.enqueue("WECHAT_STATEMENT", key, "{}", clock.instant());
                        db.update(
                                "update outbox_task set"
                                    + " status='READY',next_run_at=?,updated_at=?,version=version+1"
                                    + " where task_type='WECHAT_STATEMENT' and business_key=? and"
                                    + " status='DONE'",
                                java.sql.Timestamp.from(clock.instant()),
                                java.sql.Timestamp.from(clock.instant()),
                                key);
                    }
                });
    }

    public boolean supports(String type) {
        return "WECHAT_STATEMENT".equals(type);
    }

    public void handle(String type, String key, String payload) {
        String[] parts = key.split(":", 2);
        LocalDate date = LocalDate.parse(parts[0]);
        WechatStatements.Statement statement;
        try {
            statement = statements.download(date, parts[1]);
        } catch (com.wechat.jxsc.common.ApiException unavailable) {
            reconciliation.reconcile(
                    new WechatStatements.Statement(
                            date, parts[1], null, null, java.util.List.of(), "UNKNOWN", null));
            throw new TaskHandler.RetryLater("WECHAT_STATEMENT_UNRESOLVED", Duration.ofMinutes(15));
        }
        reconciliation.reconcile(statement);
        if (statement.availability().equals("CREATING"))
            throw new TaskHandler.RetryLater("WECHAT_STATEMENT_CREATING", Duration.ofMinutes(15));
    }
}
