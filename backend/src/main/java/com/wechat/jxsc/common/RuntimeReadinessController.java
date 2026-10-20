package com.wechat.jxsc.common;

import com.wechat.jxsc.distribution.TransferProvider;

import org.springframework.core.env.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class RuntimeReadinessController {
    private final JdbcTemplate db;
    private final Environment env;
    private final TransferProvider transfers;
    private volatile long checked;
    private volatile boolean scannerReady;

    public RuntimeReadinessController(
            JdbcTemplate db, Environment env, TransferProvider transfers) {
        this.db = db;
        this.env = env;
        this.transfers = transfers;
    }

    public record Ready(String status) {}

    public record TaskStatus(String type, String status, long count, long retries) {}

    public record RuntimeView(
            List<TaskStatus> tasks,
            long expiredAssetLeases,
            long heldOrders,
            long unresolvedTransfers,
            boolean transferRecoveryReady,
            boolean transferCreateReady) {}

    @GetMapping("/health/ready")
    public ResponseEntity<ApiResponse<Ready>> ready() {
        boolean ready;
        try {
            ready = db.queryForObject("select 1", Integer.class) == 1;
        } catch (Exception e) {
            ready = false;
        }
        if (env.acceptsProfiles(Profiles.of("prod")))
            ready = ready && scanner() && transfers.recoveryReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(ApiResponse.ok(new Ready(ready ? "UP" : "OUT_OF_SERVICE")));
    }

    @GetMapping("/admin/runtime")
    @PreAuthorize("@actorAuthorization.hasPermission('FINANCE_OPERATE')")
    public ApiResponse<RuntimeView> runtime() {
        return ApiResponse.ok(
                new RuntimeView(
                        db.query(
                                "select task_type,status,count(*) n,sum(retry_count) retries from"
                                        + " outbox_task group by task_type,status order by"
                                        + " task_type,status",
                                (r, n) ->
                                        new TaskStatus(
                                                r.getString(1),
                                                r.getString(2),
                                                r.getLong(3),
                                                r.getLong(4))),
                        db.queryForObject(
                                "select count(*) from file_asset where"
                                        + " processing_until<UTC_TIMESTAMP(6)",
                                Long.class),
                        db.queryForObject(
                                "select count(*) from store_order where financial_hold=true",
                                Long.class),
                        db.queryForObject(
                                "select count(*) from withdrawal_reconciliation where"
                                        + " status<>'RESOLVED'",
                                Long.class),
                        transfers.recoveryReady(),
                        transfers.ready()));
    }

    private synchronized boolean scanner() {
        if (System.nanoTime() - checked < 30_000_000_000L && checked != 0) return scannerReady;
        checked = System.nanoTime();
        scannerReady = false;
        try (var socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(
                            env.getRequiredProperty("jxsc.scanner.host"),
                            env.getProperty("jxsc.scanner.port", Integer.class, 3310)),
                    2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(new byte[] {'z', 'P', 'I', 'N', 'G', 0});
            byte[] reply = socket.getInputStream().readNBytes(5);
            scannerReady = Arrays.equals(reply, new byte[] {'P', 'O', 'N', 'G', 0});
        } catch (Exception ignored) {
        }
        return scannerReady;
    }
}
