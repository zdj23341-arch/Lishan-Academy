package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.tasks.TaskHandler;

import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class WithdrawalWorkflow implements TaskHandler {
    private final WithdrawalTransactions transactions;
    private final TransferProvider provider;

    public WithdrawalWorkflow(WithdrawalTransactions transactions, TransferProvider provider) {
        this.transactions = transactions;
        this.provider = provider;
    }

    public FinanceDtos.WithdrawalView create(FinanceDtos.WithdrawalCreate r, String key) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.create(r, key);
            } catch (WithdrawalTransactions.SourceSetChanged
                    | org.springframework.dao.CannotAcquireLockException e) {
                if (attempt == 2)
                    throw com.wechat.jxsc.common.commerce.CommerceSupport.conflict(
                            "FINANCE_RETRY", "Commission sources changed; retry the same request");
            }
        }
    }

    public boolean supports(String type) {
        return type.equals("WITHDRAWAL_TRANSFER")
                || type.equals("TRANSFER_RECONCILE")
                || type.equals("TRANSFER_CANCEL")
                || type.equals("WITHDRAWAL_ALERT");
    }

    public void handle(String type, String key, String payload) {
        if (type.equals("WITHDRAWAL_ALERT")) transactions.alertIfOverdue(Long.parseLong(key));
        else if (type.equals("TRANSFER_RECONCILE")) reconcile(Long.parseLong(key));
        else if (type.equals("TRANSFER_CANCEL")) cancel(Long.parseLong(key));
        else run(Long.parseLong(key));
    }

    public void cancel(long id) {
        transactions.alertIfOverdue(id);
        var action = transactions.prepareCancellation(id);
        if (action.done()) return;
        TransferProvider.Observation result;
        try {
            result =
                    action.send()
                            ? provider.cancel(action.intent())
                            : provider.query(action.intent());
        } catch (Exception unavailable) {
            throw new TaskHandler.RetryLater("TRANSFER_CANCEL_UNKNOWN", action.retry());
        }
        transactions.observe(id, result, "CANCEL_QUERY");
        if (!WithdrawalTransactions.terminal(transactions.get(id, false).status()))
            throw new TaskHandler.RetryLater("TRANSFER_CANCEL_PENDING", action.retry());
    }

    public void reconcile(long id) {
        transactions.alertIfOverdue(id);
        var action = transactions.prepareReconciliation(id);
        if (action.done()) return;
        TransferProvider.Observation result;
        try {
            result = provider.query(action.intent());
        } catch (Exception unavailable) {
            throw new TaskHandler.RetryLater("TRANSFER_RECONCILIATION_UNKNOWN", action.retry());
        }
        transactions.observe(id, result, "RECONCILIATION_QUERY");
        if (!transactions.get(id, false).reconciliation().status().equals("NEEDS_REVIEW"))
            throw new TaskHandler.RetryLater("TRANSFER_RECONCILIATION_PENDING", action.retry());
    }

    public void run(long id) {
        transactions.alertIfOverdue(id);
        WithdrawalTransactions.Action action;
        try {
            action = transactions.prepare(id);
        } catch (WithdrawalTransactions.SourceSetChanged e) {
            throw new TaskHandler.RetryLater("FINANCE_RETRY", Duration.ofSeconds(5));
        }
        if (action.done()) return;
        TransferProvider.Observation result;
        try {
            result =
                    action.send()
                            ? provider.send(action.intent())
                            : provider.query(action.intent());
        } catch (Exception unavailable) {
            throw new TaskHandler.RetryLater("TRANSFER_TRANSPORT_UNKNOWN", action.retry());
        }
        transactions.observe(id, result, action.send() ? "SEND" : "QUERY");
        if (!WithdrawalTransactions.terminal(transactions.get(id, false).status()))
            throw new TaskHandler.RetryLater("TRANSFER_PENDING", action.retry());
    }
}
