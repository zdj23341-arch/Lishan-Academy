package com.wechat.jxsc.payment.wechat;

import com.wechat.jxsc.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/wechat")
@PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
public class WechatOperationsController {
    private final WechatStatementWorker worker;
    private final JdbcTemplate db;

    public WechatOperationsController(WechatStatementWorker worker, JdbcTemplate db) {
        this.worker = worker;
        this.db = db;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "WechatReconciliationIssue")
    public record WechatReconciliationIssue(
            String id,
            String channel,
            String businessNo,
            String resourceType,
            String issueCode,
            LocalDate billDate,
            @io.swagger.v3.oas.annotations.media.Schema(
                            types = {"integer", "null"},
                            format = "int64")
                    Long localAmountFen,
            @io.swagger.v3.oas.annotations.media.Schema(
                            types = {"integer", "null"},
                            format = "int64")
                    Long channelAmountFen,
            String status) {}

    @PostMapping("/statements/{date}/refresh")
    public ApiResponse<Void> refresh(@PathVariable LocalDate date) {
        worker.enqueue(date);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reconciliation-issues")
    public ApiResponse<PageResult<WechatReconciliationIssue>> issues(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var q = new PageQuery(page, pageSize);
        List<WechatReconciliationIssue> rows =
                db.query(
                        "select * from reconciliation_issue order by id desc limit ? offset ?",
                        (r, n) ->
                                new WechatReconciliationIssue(
                                        r.getString("id"),
                                        r.getString("channel"),
                                        r.getString("business_no"),
                                        r.getString("resource_type"),
                                        r.getString("issue_code"),
                                        r.getDate("bill_date").toLocalDate(),
                                        r.getObject("local_amount_fen", Long.class),
                                        r.getObject("channel_amount_fen", Long.class),
                                        r.getString("status")),
                        q.pageSize(),
                        q.offset());
        return ApiResponse.ok(
                new PageResult<>(
                        rows,
                        q.page(),
                        q.pageSize(),
                        db.queryForObject(
                                "select count(*) from reconciliation_issue", Long.class)));
    }
}
