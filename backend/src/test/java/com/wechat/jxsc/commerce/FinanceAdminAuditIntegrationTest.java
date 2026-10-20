package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.distribution.WithdrawalWorkflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.*;

@Import(FinanceSqlGate.class)
class FinanceAdminAuditIntegrationTest extends DistributionTestSupport {
    @Autowired WithdrawalWorkflow workflow;

    JsonNode audit(String action, String resource) throws Exception {
        var rows =
                jdbc.queryForList(
                        "select * from operation_audit_log where action=? and resource_id=? order"
                                + " by id desc limit 1",
                        action,
                        resource);
        assertThat(rows).as(action + " audit evidence").hasSize(1);
        var row = rows.getFirst();
        var node = json.createObjectNode();
        node.set(
                "before",
                row.get("before_json") == null
                        ? json.nullNode()
                        : json.readTree(row.get("before_json").toString()));
        node.set(
                "after",
                row.get("after_json") == null
                        ? json.nullNode()
                        : json.readTree(row.get("after_json").toString()));
        node.put("actorType", row.get("actor_type").toString());
        node.put("actorId", row.get("actor_id").toString());
        node.put("reason", row.get("reason").toString());
        node.put("requestId", row.get("request_id").toString());
        return node;
    }

    @Test
    void distributorEditAuditsLockedBeforeAndActualAfterAndRejectedVersionAddsNothing()
            throws Exception {
        String promoter = freshPromoter(), d = did(promoter);
        var result =
                call(
                        "PATCH",
                        "/admin/distributors/" + d,
                        a,
                        Map.of(
                                "version",
                                0,
                                "status",
                                "ACTIVE",
                                "riskBlocked",
                                true,
                                "reason",
                                "Risk investigation"),
                        200);
        var log = audit("DISTRIBUTOR_EDIT", d);
        assertThat(log.path("before").path("riskBlocked").asBoolean()).isFalse();
        assertThat(log.path("before").path("version").asLong(-1)).isZero();
        assertThat(log.path("after")).isEqualTo(result);
        assertThat(log.path("reason").asText()).isEqualTo("Risk investigation");
        assertThat(log.path("actorType").asText()).isEqualTo("ADMIN");
        assertThat(log.path("requestId").asText()).isNotBlank();
        call(
                "PATCH",
                "/admin/distributors/" + d,
                a,
                Map.of(
                        "version",
                        0,
                        "status",
                        "ACTIVE",
                        "riskBlocked",
                        false,
                        "reason",
                        "Stale edit"),
                409);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from operation_audit_log where"
                                        + " action='DISTRIBUTOR_EDIT' and resource_id=?",
                                Long.class,
                                d))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select risk_blocked from distributor where id=?",
                                Boolean.class,
                                d))
                .isTrue();
    }

    @Test
    void rulesAuditIncludesOmittedDisabledRuleInBothSnapshots() throws Exception {
        String first = did(freshPromoter()),
                second = did(freshPromoter()),
                product = book(a, 5, 1000);
        rule(product, first, second);
        var before = call("GET", "/admin/products/" + product + "/distributors", a, null, 200);
        var after =
                call(
                        "PUT",
                        "/admin/products/" + product + "/distributors",
                        a,
                        Map.of(
                                "version",
                                1,
                                "rules",
                                List.of(
                                        Map.of(
                                                "distributorId",
                                                first,
                                                "rateBps",
                                                900,
                                                "enabled",
                                                true)),
                                "reason",
                                "Replace promoter rules"),
                        200);
        var log = audit("DISTRIBUTION_RULES", product);
        assertThat(log.path("before")).isEqualTo(before);
        assertThat(log.path("after")).isEqualTo(after);
        assertThat(after.path("rules").size()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select enabled from product_distributor_rule where product_id=?"
                                        + " and distributor_id=?",
                                Boolean.class,
                                product,
                                second))
                .isFalse();
    }

    @Test
    void partialSettingsPatchAuditsCompleteBeforeAndAfter() throws Exception {
        var before = call("GET", "/admin/settings", a, null, 200);
        var after =
                call(
                        "PATCH",
                        "/admin/settings",
                        a,
                        Map.of(
                                "version",
                                before.path("version").asLong(),
                                "values",
                                Map.of("minFen", 200),
                                "reason",
                                "Raise minimum"),
                        200);
        var log = audit("SETTINGS_EDIT", "1");
        assertThat(log.path("before")).isEqualTo(before);
        assertThat(log.path("after")).isEqualTo(after);
        assertThat(after.path("limits").path("maxFen"))
                .isEqualTo(before.path("limits").path("maxFen"));
        jdbc.update("update finance_settings set min_fen=100 where id=1");
    }

    @Test
    void auditInsertFailureRollsBackAdministrativeEdit() throws Exception {
        String d = did(freshPromoter());
        var reached = new java.util.concurrent.atomic.AtomicBoolean();
        FinanceSqlGate.observer =
                (sql, after) -> {
                    if (after && sql.startsWith("insert into operation_audit_log")) {
                        reached.set(true);
                        throw new java.sql.SQLException("Test audit storage failure");
                    }
                };
        try {
            call(
                    "PATCH",
                    "/admin/distributors/" + d,
                    a,
                    Map.of(
                            "version",
                            0,
                            "status",
                            "ACTIVE",
                            "riskBlocked",
                            true,
                            "reason",
                            "Rollback test"),
                    500);
            assertThat(reached).isTrue();
        } finally {
            FinanceSqlGate.observer = (sql, after) -> {};
        }
        assertThat(
                        jdbc.queryForObject(
                                "select risk_blocked from distributor where id=?",
                                Boolean.class,
                                d))
                .isFalse();
        assertThat(jdbc.queryForObject("select version from distributor where id=?", Long.class, d))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from operation_audit_log where"
                                        + " action='DISTRIBUTOR_EDIT' and resource_id=?",
                                Long.class,
                                d))
                .isZero();
    }

    @Test
    void actualAdminRefreshHasOneSafeActorAuditAndTerminalNoOpHasNone() throws Exception {
        String promoter = freshPromoter(), d = did(promoter);
        jdbc.update("update commission_account set available_fen=1000 where distributor_id=?", d);
        String id =
                write("/distribution/withdrawals", promoter, Map.of("amountFen", 1000))
                        .path("id")
                        .asText();
        var response =
                mvc.perform(
                                post("/api/v1/admin/withdrawals/" + id + "/refresh")
                                        .header("Authorization", "Bearer " + a))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var log = audit("WITHDRAWAL_REFRESH", id);
        long actorId =
                jdbc.queryForObject(
                        "select actor_id from login_session where token_hash=?",
                        Long.class,
                        com.wechat.jxsc.auth.TokenCodec.hash(a));
        assertThat(log.path("actorId").asText()).isEqualTo("" + actorId);
        assertThat(log.path("actorType").asText()).isEqualTo("ADMIN");
        assertThat(log.path("requestId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(log.path("reason").asText()).isNotBlank();
        assertThat(log.path("before").path("lastRefreshAt").isNull()).isTrue();
        assertThat(log.path("after").path("lastRefreshAt").asText())
                .isEqualTo(clock.now.toString());
        assertThat(log.toString())
                .doesNotContain("recipient", "package", "openid", "cipher", "merchant");
        call("POST", "/admin/withdrawals/" + id + "/refresh", a, null, 429);
        workflow.run(Long.parseLong(id));
        call("POST", "/admin/withdrawals/" + id + "/refresh", a, null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from operation_audit_log where"
                                        + " action='WITHDRAWAL_REFRESH' and resource_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
    }
}
