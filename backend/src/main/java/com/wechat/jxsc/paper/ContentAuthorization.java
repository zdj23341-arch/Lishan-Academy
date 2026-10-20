package com.wechat.jxsc.paper;

import static com.wechat.jxsc.membership.EntitlementLedger.time;

import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.storage.*;
import com.wechat.jxsc.membership.*;

import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Database-only authorization. Storage reads happen after this service's transaction commits. */
@Service
public class ContentAuthorization {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    private final com.wechat.jxsc.analytics.DownloadProvenance provenance;
    private final JdbcTemplate db;
    private final EntitlementAccess access;
    private final EntitlementLedger ledger;
    private final Clock clock;

    public ContentAuthorization(
            JdbcTemplate db,
            EntitlementAccess access,
            EntitlementLedger ledger,
            Clock clock,
            com.wechat.jxsc.analytics.DownloadProvenance provenance) {
        this.db = db;
        this.access = access;
        this.ledger = ledger;
        this.clock = clock;
        this.provenance = provenance;
    }

    public enum Action {
        PREVIEW,
        DOWNLOAD
    }

    public record Request(@NotNull Action action) {}

    public record FileTicket(AssetDtos.Summary file, String url, Instant expiresAt) {}

    public record PlaybackTicket(String url, Instant expiresAt, int resumeSeconds) {}

    public record Content(
            AssetDtos.Summary file,
            AssetService.ProtectedObject object,
            String action,
            Instant expiresAt) {}

    private record Asset(AssetDtos.Summary summary, StorageProvider.SealedObject sealed) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public FileTicket file(long paper, long file, Action action, String requestDigest) {
        // REQUIRED cannot override an existing transaction's isolation. Fail before doing work
        // rather than silently classifying the selected current grant from an older RR snapshot.
        if (!Objects.equals(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getCurrentTransactionIsolationLevel(),
                java.sql.Connection.TRANSACTION_READ_COMMITTED))
            throw new org.springframework.transaction.IllegalTransactionStateException(
                    "File authorization requires a READ COMMITTED transaction");
        long user = EntitlementAccess.customer();
        lifecycle.requireActive(user);
        access.paperRelation(paper, file);
        EntitlementAccess.require(access.membership(user), "Active membership is required");
        ledger.lock(user);
        access.paperRelation(paper, file, true);
        var right = access.membership(user, true);
        EntitlementAccess.require(right, "Active membership is required");
        var asset = asset(file, "PAPER_ATTACHMENT");
        Instant expiry = expiry(120, right.expiresAt());
        String url = save(user, paper, null, file, action.name(), asset, expiry);
        provenance.record(user, file, paper, action.name(), requestDigest);
        return new FileTicket(asset.summary(), url, expiry);
    }

    @Transactional
    public PlaybackTicket play(long course, long chapter) {
        Long user = EntitlementAccess.viewer();
        if (user != null) lifecycle.requireActive(user);
        var c = access.chapter(course, chapter);
        var right = access.playback(user, c, false);
        if (c.trial()) user = null;
        else {
            ledger.lock(user);
        }
        c = access.chapter(course, chapter, true);
        right = access.playback(user, c, user != null);
        var asset = asset(c.assetId(), "COURSE_VIDEO");
        Instant expiry = expiry(300, right.expiresAt());
        return new PlaybackTicket(
                save(user, null, chapter, c.assetId(), "PLAY", asset, expiry),
                expiry,
                Math.min(
                        access.position(EntitlementAccess.viewer(), chapter), c.durationSeconds()));
    }

    @Transactional
    public Content redeem(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw EntitlementAccess.missing();
        var rows =
                db.query(
                        "select * from content_capability where token_hash=?",
                        (r, n) -> {
                            Map<String, Object> values = new HashMap<>();
                            for (String column :
                                    List.of(
                                            "user_id",
                                            "asset_id",
                                            "paper_id",
                                            "chapter_id",
                                            "action",
                                            "storage_key",
                                            "sealed_version"))
                                values.put(column, r.getObject(column));
                            values.put("expires_at", r.getTimestamp("expires_at").toInstant());
                            return values;
                        },
                        TokenCodec.hash(token));
        if (rows.isEmpty()) throw EntitlementAccess.missing();
        var row = rows.getFirst();
        Instant expires = (Instant) row.get("expires_at");
        requireUnexpired(expires);
        Long user = row.get("user_id") == null ? null : ((Number) row.get("user_id")).longValue();
        if (user != null) lifecycle.requireActive(user);
        if (user != null) ledger.lock(user);
        long file = ((Number) row.get("asset_id")).longValue();
        String action = row.get("action").toString();
        String purpose;
        EntitlementAccess.Right right;
        if (row.get("paper_id") != null) {
            access.paperRelation(((Number) row.get("paper_id")).longValue(), file, true);
            right = access.membership(user, true);
            EntitlementAccess.require(right, "Active membership is required");
            purpose = "PAPER_ATTACHMENT";
        } else {
            var c = access.chapter(((Number) row.get("chapter_id")).longValue(), true);
            right = access.playback(user, c, user != null);
            if (!Objects.equals(c.assetId(), file)) throw EntitlementAccess.contentUnavailable();
            purpose = "COURSE_VIDEO";
        }
        // A refund may shorten the surviving term after this bearer was issued.
        if (right.expiresAt() != null && right.expiresAt().isBefore(expires))
            expires = right.expiresAt();
        var asset = asset(file, purpose);
        if (!asset.sealed().key().equals(row.get("storage_key"))
                || !asset.sealed().version().equals(row.get("sealed_version")))
            throw EntitlementAccess.contentUnavailable();
        requireUnexpired(expires);
        return new Content(
                asset.summary(),
                new AssetService.ProtectedObject(asset.summary().mime(), asset.sealed()),
                action,
                expires);
    }

    public void requireUnexpired(Instant expires) {
        if (!clock.instant().isBefore(expires))
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "ENTITLEMENT_EXPIRED",
                    "Content authorization expired");
    }

    private Instant expiry(int seconds, Instant rightEnd) {
        Instant end = clock.instant().plusSeconds(seconds);
        if (rightEnd != null && rightEnd.isBefore(end)) end = rightEnd;
        end = end.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        requireUnexpired(end);
        return end;
    }

    private String save(
            Long user,
            Long paper,
            Long chapter,
            long file,
            String action,
            Asset asset,
            Instant expiry) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update(
                "insert into"
                    + " content_capability(token_hash,user_id,asset_id,paper_id,chapter_id,action,storage_key,sealed_version,expires_at)"
                    + " values (?,?,?,?,?,?,?,?,?)",
                TokenCodec.hash(token),
                user,
                file,
                paper,
                chapter,
                action,
                asset.sealed().key(),
                asset.sealed().version(),
                time(expiry));
        return "/api/v1/protected-content/" + token;
    }

    private Asset asset(long id, String purpose) {
        var rows =
                db.queryForList(
                        "select"
                            + " id,original_name,extension,mime,bytes,status,purpose,storage_key,sealed_version"
                            + " from file_asset where id=? for share",
                        id);
        if (rows.isEmpty()) throw EntitlementAccess.missing();
        var row = rows.getFirst();
        if (!purpose.equals(row.get("purpose"))) throw EntitlementAccess.missing();
        if (!"READY".equals(row.get("status")) || row.get("sealed_version") == null)
            throw EntitlementAccess.contentUnavailable();
        var summary =
                new AssetDtos.Summary(
                        Long.toString(id),
                        row.get("original_name").toString(),
                        row.get("extension").toString(),
                        row.get("mime").toString(),
                        ((Number) row.get("bytes")).longValue());
        return new Asset(
                summary,
                new StorageProvider.SealedObject(
                        row.get("storage_key").toString(),
                        row.get("sealed_version").toString(),
                        summary.bytes()));
    }
}
