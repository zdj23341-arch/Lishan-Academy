package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@Component
@Conditional(DevTransferCondition.class)
public class DevTransferProvider implements TransferProvider {
    private final FinanceStore f;

    public DevTransferProvider(FinanceStore f) {
        this.f = f;
    }

    public boolean ready() {
        return true;
    }

    public String channel() {
        return "DEV";
    }

    public String appId() {
        return "DEV";
    }

    public String merchantId() {
        return "DEV-MERCHANT";
    }

    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Transfer transport inside transaction");
    }

    public Observation send(Intent i) {
        outside();
        requireOwn(i);
        f.db.update(
                "insert into transfer_simulation(withdrawal_id,scenario,provider_state)"
                        + " values(?,'SUCCESS','SUCCESS') on duplicate key update"
                        + " withdrawal_id=withdrawal_id",
                i.id());
        f.db.update(
                "update transfer_simulation set send_count=send_count+1 where withdrawal_id=?",
                i.id());
        var row = f.one("select * from transfer_simulation where withdrawal_id=?", i.id());
        if (str(row, "scenario").equals("TIMEOUT"))
            throw new IllegalStateException("SIMULATED_TIMEOUT");
        return observation(i, str(row, "provider_state"));
    }

    public Observation query(Intent i) {
        outside();
        requireOwn(i);
        f.db.update(
                "insert into transfer_simulation(withdrawal_id,scenario,provider_state)"
                        + " values(?,'UNKNOWN','UNKNOWN') on duplicate key update"
                        + " query_count=query_count+1",
                i.id());
        return observation(
                i,
                str(
                        f.one("select * from transfer_simulation where withdrawal_id=?", i.id()),
                        "provider_state"));
    }

    public Observation observation(Intent i, String state) {
        requireOwn(i);
        return new Observation(
                UUID.randomUUID().toString(),
                i.outBillNo(),
                i.amountFen(),
                appId(),
                merchantId(),
                i.recipient(),
                channel(),
                "DEV" + i.id(),
                state,
                state.equals("WAIT_USER_CONFIRM") ? "DEV-PACKAGE-" + i.id() : null,
                true);
    }

    public void scenario(Intent intent, String scenario, String state) {
        outside();
        requireOwn(intent);
        f.db.update(
                "insert into transfer_simulation(withdrawal_id,scenario,provider_state)"
                        + " values(?,?,?) on duplicate key update"
                        + " scenario=values(scenario),provider_state=values(provider_state)",
                intent.id(),
                scenario,
                state);
    }

    private void requireOwn(Intent intent) {
        if (!channel().equals(intent.channel())
                || !appId().equals(intent.appId())
                || !merchantId().equals(intent.merchantId()))
            throw new com.wechat.jxsc.common.ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "WITHDRAWAL_NOT_READY",
                    "Intent does not belong to the configured simulator");
    }
}
