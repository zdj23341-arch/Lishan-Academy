package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import org.springframework.stereotype.Service;

@Service
public class DistributionSummaryService {
    private final FinanceStore f;
    private final DistributionAdministration admin;
    private final TransferProvider provider;

    public DistributionSummaryService(
            FinanceStore f, DistributionAdministration admin, TransferProvider provider) {
        this.f = f;
        this.admin = admin;
        this.provider = provider;
    }

    public FinanceDtos.Summary summary() {
        var d = admin.own();
        long id = n(d, "id");
        var a = f.one("select * from commission_account where distributor_id=?", id);
        String reason = null;
        if (!str(d, "status").equals("ACTIVE")) reason = "DISTRIBUTOR_DISABLED";
        else if (bool(d, "risk_blocked")) reason = "RISK_BLOCKED";
        else if (n(a, "debt_fen") > 0) reason = "OUTSTANDING_DEBT";
        else if (f.db.queryForObject(
                        "select count(*) from withdrawal_request w where distributor_id=? and"
                            + " error_code='TRANSFER_TERMINAL_CONFLICT' and not exists(select 1"
                            + " from economic_resolution e where e.resource_type='WITHDRAWAL' and"
                            + " e.transaction_id=w.id)",
                        Long.class,
                        id)
                > 0) reason = "FINANCIAL_RECONCILIATION_REQUIRED";
        else if (!bool(d, "receipt_ready") || !provider.ready()) reason = "WITHDRAWAL_NOT_READY";
        else if (f.db.queryForObject(
                        "select count(*) from commission_ledger c join store_order o on"
                            + " o.id=c.order_id where c.distributor_id=? and o.financial_hold=true",
                        Long.class,
                        id)
                > 0) reason = "FINANCIAL_HOLD";
        long products =
                f.db.queryForObject(
                        "select count(*) from product_distributor_rule r join product p on"
                            + " p.id=r.product_id where r.distributor_id=? and r.enabled=true and"
                            + " p.status='ON_SALE' and r.effective_at<=? and (r.expires_at is null"
                            + " or r.expires_at>?)",
                        Long.class,
                        id,
                        f.now(),
                        f.now());
        return new FinanceDtos.Summary(
                "" + id,
                str(d, "status"),
                bool(d, "receipt_ready"),
                n(a, "pending_fen"),
                n(a, "available_fen"),
                n(a, "withdrawing_fen"),
                n(a, "paid_out_fen"),
                n(a, "debt_fen"),
                reason == null,
                reason,
                admin.settings().limits(),
                str(d, "status").equals("ACTIVE") ? products : 0,
                f.db.queryForObject(
                        "select coalesce(sum(observed_paid_out_fen),0) from"
                                + " withdrawal_reconciliation where distributor_id=? and"
                                + " status<>'RESOLVED'",
                        Long.class,
                        id));
    }
}
