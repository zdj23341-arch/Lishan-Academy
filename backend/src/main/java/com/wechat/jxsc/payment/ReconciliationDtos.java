package com.wechat.jxsc.payment;

import java.util.List;

public final class ReconciliationDtos {
    public record Issue(
            String resourceType,
            String transactionId,
            String reason,
            String status,
            String lastQueryState,
            boolean successObserved,
            long amountFen) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ReconciliationView")
    public record View(
            long confirmedRefundFen,
            long possibleInFlightRefundFen,
            long confirmedExceptionalRefundFen,
            long possibleExceptionalRefundFen,
            boolean overBudget,
            List<Issue> issues) {}
}
