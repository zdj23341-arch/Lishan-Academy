package com.wechat.jxsc.payment;

import jakarta.persistence.*;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.util.*;

@Component
@Conditional(DevPaymentCondition.class)
public class DevPaymentProvider implements PaymentProvider {
    @PersistenceContext private EntityManager em;
    private final TransactionTemplate tx;

    public DevPaymentProvider(PlatformTransactionManager manager) {
        tx = new TransactionTemplate(manager);
    }

    public String channel() {
        return "DEV";
    }

    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider called within database transaction");
    }

    public void scenario(Intent intent, String kind, String scenario) {
        outside();
        if (!intent.channel().equals("DEV")) throw new IllegalArgumentException("DEV orders only");
        tx.executeWithoutResult(
                t ->
                        em.createNativeQuery(
                                        "INSERT INTO"
                                            + " dev_payment_state(business_no,kind,scenario,channel_id,changed_at)"
                                            + " VALUES (:b,:k,:s,:c,UTC_TIMESTAMP(6)) ON DUPLICATE"
                                            + " KEY UPDATE"
                                            + " scenario=IF(scenario='SUCCESS','SUCCESS',VALUES(scenario)),changed_at=UTC_TIMESTAMP(6)")
                                .setParameter("b", intent.businessNo())
                                .setParameter("k", kind)
                                .setParameter("s", scenario)
                                .setParameter("c", "DEV-" + intent.businessNo())
                                .executeUpdate());
    }

    private Result result(Intent i, boolean closing) {
        outside();
        String scenario =
                tx.execute(
                        t -> {
                            var rows =
                                    em.createNativeQuery(
                                                    "SELECT scenario FROM dev_payment_state WHERE"
                                                            + " business_no=:b")
                                            .setParameter("b", i.businessNo())
                                            .getResultList();
                            return rows.isEmpty() ? "PROCESSING" : rows.getFirst().toString();
                        });
        State state =
                switch (scenario) {
                    case "SUCCESS" -> State.SUCCEEDED;
                    case "CLOSED" -> State.CLOSED;
                    case "FAILURE" -> State.FAILED;
                    case "TIMEOUT" -> State.UNKNOWN;
                    default -> closing ? State.CLOSED : State.PROCESSING;
                };
        if (closing && state == State.CLOSED) scenario(i, "PAYMENT", "CLOSED");
        return new Result(
                state,
                "DEV-" + i.businessNo(),
                i.amountFen(),
                "CNY",
                "DEV",
                "DEV-MERCHANT",
                Map.of("simulated", "true"));
    }

    public Result prepay(Intent i) {
        return result(i, false);
    }

    public Result query(Intent i) {
        return result(i, false);
    }

    public Result close(Intent i) {
        return result(i, true);
    }

    public Result refund(Intent i) {
        return result(i, false);
    }

    public Result queryRefund(Intent i) {
        return result(i, false);
    }
}
