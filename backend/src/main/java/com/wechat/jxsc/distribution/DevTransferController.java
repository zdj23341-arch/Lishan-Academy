package com.wechat.jxsc.distribution;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.tasks.TaskHandler;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dev/withdrawals")
@Conditional(DevTransferCondition.class)
public class DevTransferController {
    private final WithdrawalTransactions transactions;
    private final WithdrawalWorkflow workflow;
    private final DevTransferProvider provider;

    public DevTransferController(
            WithdrawalTransactions transactions,
            WithdrawalWorkflow workflow,
            DevTransferProvider provider) {
        this.transactions = transactions;
        this.workflow = workflow;
        this.provider = provider;
    }

    @Schema(name = "DevTransferScenario")
    public record Scenario(
            @NotBlank
                    @Pattern(
                            regexp =
                                    "SUCCESS|TIMEOUT|PROCESSING|USER_CONFIRM|FAILURE|CLOSED|SUCCESS_CALLBACK|FAILURE_CALLBACK|CONFIRM_RECEIPT|SUCCESS_QUERY")
                    String scenario) {}

    @PostMapping("/{id}/simulate")
    public ApiResponse<FinanceDtos.WithdrawalView> simulate(
            @PathVariable String id, @Valid @RequestBody Scenario r) {
        long wid = CommerceSupport.id(id);
        var owned = transactions.owned(wid);
        var intent = transactions.intent(owned);
        String state =
                switch (r.scenario()) {
                    case "SUCCESS", "SUCCESS_CALLBACK", "CONFIRM_RECEIPT", "SUCCESS_QUERY" ->
                            "SUCCESS";
                    case "FAILURE", "FAILURE_CALLBACK" -> "FAIL";
                    case "USER_CONFIRM" -> "WAIT_USER_CONFIRM";
                    case "CLOSED" -> "CANCELLED";
                    case "PROCESSING" -> "TRANSFERING";
                    default -> "UNKNOWN";
                };
        provider.scenario(intent, r.scenario(), state);
        if (r.scenario().endsWith("CALLBACK") || r.scenario().equals("CONFIRM_RECEIPT"))
            transactions.observe(wid, provider.observation(intent, state), "DEV_CALLBACK");
        else {
            if (r.scenario().equals("SUCCESS_QUERY")) transactions.refresh(wid, true);
            try {
                workflow.run(wid);
            } catch (TaskHandler.RetryLater pending) {
            }
        }
        return ApiResponse.ok(transactions.get(wid, true));
    }
}
