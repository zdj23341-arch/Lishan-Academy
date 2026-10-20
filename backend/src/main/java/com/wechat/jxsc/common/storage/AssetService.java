package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;

import jakarta.persistence.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class AssetService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    @PersistenceContext private EntityManager em;
    private final StorageProvider storage;
    private final FormatScanner scanner;
    private final Clock clock;
    private final ContentSupport support;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private static final Map<String, String> MIME =
            Map.of(
                    "PNG",
                    "image/png",
                    "JPG",
                    "image/jpeg",
                    "JPEG",
                    "image/jpeg",
                    "WEBP",
                    "image/webp",
                    "PDF",
                    "application/pdf",
                    "DOC",
                    "application/msword",
                    "DOCX",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "MP4",
                    "video/mp4");

    public AssetService(
            StorageProvider storage,
            FormatScanner scanner,
            Clock clock,
            ContentSupport support,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.storage = storage;
        this.scanner = scanner;
        this.clock = clock;
        this.support = support;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    @Transactional
    public AssetDtos.Ticket presign(AssetDtos.Presign r, boolean admin) {
        var actor = CurrentActor.require();
        actor.requireType(admin ? CurrentActor.Type.ADMIN : CurrentActor.Type.CUSTOMER);
        if (!admin) lifecycle.customer();
        if (!admin && r.purpose() != AssetDtos.Purpose.AFTERSALE_EVIDENCE)
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "Customers may upload only their aftersale evidence");
        if (admin && r.purpose() == AssetDtos.Purpose.AFTERSALE_EVIDENCE)
            throw ContentSupport.invalid("Use customer aftersale upload");
        String name = r.name();
        if (name.chars().anyMatch(c -> c < 32 || c == 127))
            throw ContentSupport.invalid("Invalid filename");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!r.mime().equals(MIME.get(extension)))
            throw ContentSupport.invalid("MIME and extension must match");
        boolean image = Set.of("PNG", "JPEG", "JPG", "WEBP").contains(extension);
        if ((r.purpose() == AssetDtos.Purpose.PUBLIC_IMAGE
                        || r.purpose() == AssetDtos.Purpose.AFTERSALE_EVIDENCE)
                && !image) throw ContentSupport.invalid("Purpose requires an image");
        if (r.purpose() == AssetDtos.Purpose.COURSE_VIDEO && !extension.equals("MP4")
                || r.purpose() != AssetDtos.Purpose.COURSE_VIDEO && extension.equals("MP4"))
            throw ContentSupport.invalid("Invalid video purpose");
        long limit = image ? 10485760L : extension.equals("MP4") ? 2147483648L : 52428800L;
        if (r.bytes() <= 0 || r.bytes() > limit) throw ContentSupport.invalid("File too large");
        var a = new FileAsset();
        a.ownerType = actor.type().name();
        a.ownerId = actor.id();
        a.purpose = r.purpose().name();
        a.storageKey = UUID.randomUUID().toString().replace("-", "");
        a.originalName = name;
        a.extension = extension;
        a.mime = r.mime();
        a.expectedBytes = r.bytes();
        a.status = "PENDING";
        a.scanStatus = "PENDING";
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        a.uploadTokenHash = hash(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        a.uploadExpiresAt = clock.instant().plusSeconds(900);
        em.persist(a);
        em.flush();
        String url =
                storage.uploadUrl(
                        a.getId(), a.storageKey, a.mime, a.expectedBytes, a.uploadExpiresAt);
        audit(a, "ASSET_PRESIGN", -1);
        return new AssetDtos.Ticket(a.getId().toString(), url, token, a.uploadExpiresAt);
    }

    public void upload(long id, String token, InputStream body) throws IOException {
        if (storage.directUpload()) throw ContentSupport.invalid("Use the signed COS PUT URL");
        var claim = tx.execute(status -> prepareUpload(id, token));
        try {
            storage.writeOnce(claim.key(), body, claim.expected());
            tx.executeWithoutResult(
                    status -> {
                        var a = owned(id);
                        fence(a, claim);
                        a.status = "UPLOADED";
                        a.processingToken = null;
                        a.processingUntil = null;
                        a.scanStatus = "PENDING";
                        audit(a, "ASSET_UPLOADED", a.getVersion());
                    });
        } catch (IOException | RuntimeException e) {
            failed(id, claim);
            throw e;
        }
    }

    private Claim prepareUpload(long id, String token) {
        var actor = CurrentActor.require();
        if (actor.type() == CurrentActor.Type.ADMIN
                && !actor.permissions().contains("CONTENT_EDIT"))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "FORBIDDEN", "Content edit permission required");
        var a = owned(id);
        if (!MessageDigest.isEqual(
                        a.uploadTokenHash.getBytes(),
                        hash(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).getBytes())
                || !a.uploadExpiresAt.isAfter(clock.instant()))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "FORBIDDEN", "Upload credential expired or invalid");
        if (!a.status.equals("PENDING"))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "FILE_ALREADY_UPLOADED",
                    "Upload credentials are single use");
        return claim(a);
    }

    public AssetDtos.View complete(long id) {
        var prepared =
                tx.execute(
                        status -> {
                            var a = owned(id);
                            if (a.status.equals("READY")) return new Prepared(view(a), null);
                            if (!(a.status.equals("UPLOADED")
                                    || storage.directUpload() && a.status.equals("PENDING")))
                                throw new ApiException(
                                        HttpStatus.CONFLICT,
                                        "FILE_NOT_READY",
                                        "Upload is not ready");
                            var claim = claim(a);
                            a.status = "UPLOADED";
                            return new Prepared(null, claim);
                        });
        if (prepared.ready() != null) return prepared.ready();
        var claim = prepared.claim();
        try {
            var object = storage.seal(claim.key());
            if (object.bytes() != claim.expected()) return rejected(id, claim, "SIZE_REJECTED");
            FormatScanner.ScanResult scan;
            try (var source = storage.open(object)) {
                scan = scanner.scan(claim.extension(), source);
            }
            if (!scan.accepted()) return rejected(id, claim, scan.status());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var source = storage.open(object)) {
                byte[] b = new byte[65536];
                int n;
                long read = 0;
                while ((n = source.read(b)) != -1) {
                    read += n;
                    if (read > claim.expected())
                        throw new IOException("Object exceeds declared size");
                    digest.update(b, 0, n);
                }
                if (read != claim.expected()) throw new IOException("Truncated object");
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            return tx.execute(
                    status -> {
                        var a = owned(id);
                        fence(a, claim);
                        a.storageKey = object.key();
                        a.sealedVersion = object.version();
                        a.bytes = object.bytes();
                        a.sha256 = hash;
                        a.scanStatus = scan.status();
                        a.status = "READY";
                        a.processingToken = null;
                        a.processingUntil = null;
                        audit(a, "ASSET_READY", a.getVersion());
                        em.flush();
                        return view(a);
                    });
        } catch (IOException | NoSuchAlgorithmException e) {
            failed(id, claim);
            throw StorageConfiguration.unavailable();
        } catch (RuntimeException e) {
            if (!(e instanceof RejectedAsset)) failed(id, claim);
            throw e;
        }
    }

    private record Claim(String key, String extension, long expected, String token) {}

    private record Prepared(AssetDtos.View ready, Claim claim) {}

    private Claim claim(FileAsset a) {
        if (a.processingUntil != null && a.processingUntil.isAfter(clock.instant()))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "FILE_PROCESSING",
                    "Asset verification is in progress; retry later");
        a.processingToken = UUID.randomUUID().toString();
        a.processingUntil = clock.instant().plusSeconds(900);
        a.scanStatus = "PROCESSING";
        return new Claim(a.storageKey, a.extension, a.expectedBytes, a.processingToken);
    }

    private void fence(FileAsset a, Claim claim) {
        if (!Objects.equals(a.processingToken, claim.token())
                || a.status.equals("DISABLED")
                || a.processingUntil == null
                || !a.processingUntil.isAfter(clock.instant()))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "FILE_PROCESSING_CHANGED",
                    "Asset verification lease changed");
    }

    private void failed(long id, Claim claim) {
        tx.executeWithoutResult(
                status -> {
                    var a = owned(id);
                    if (Objects.equals(a.processingToken, claim.token())
                            && !a.status.equals("DISABLED")) {
                        a.processingToken = null;
                        a.processingUntil = null;
                        a.scanStatus = "VERIFICATION_ERROR";
                        audit(a, "ASSET_VERIFICATION_RETRY", a.getVersion());
                    }
                });
    }

    private AssetDtos.View rejected(long id, Claim claim, String scanStatus) {
        tx.executeWithoutResult(
                status -> {
                    var a = owned(id);
                    fence(a, claim);
                    reject(a);
                    a.scanStatus = scanStatus;
                });
        throw new RejectedAsset();
    }

    private void reject(FileAsset a) {
        a.status = "REJECTED";
        a.scanStatus = "FORMAT_REJECTED";
        a.processingToken = null;
        a.processingUntil = null;
        audit(a, "ASSET_REJECTED", a.getVersion());
        em.flush();
    }

    static class RejectedAsset extends ApiException {
        RejectedAsset() {
            super(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_REJECTED",
                    "Stored file does not match a supported format");
        }
    }

    private FileAsset owned(long id) {
        var a = em.find(FileAsset.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (a == null) throw ContentSupport.missing();
        var actor = CurrentActor.require();
        if (!a.ownerType.equals(actor.type().name()) || !a.ownerId.equals(actor.id()))
            throw ContentSupport.missing();
        return a;
    }

    @Transactional(readOnly = true)
    public PageResult<AssetDtos.AdminView> adminList(PageQuery page) {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        String where =
                " where (a.ownerType='ADMIN' and a.ownerId=:owner) or (a.ownerType='SYSTEM' and"
                        + " a.seedKey is not null)";
        var rows =
                em.createQuery(
                                "select a from FileAsset a" + where + " order by a.id desc",
                                FileAsset.class)
                        .setParameter("owner", actor.id())
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList();
        long total =
                em.createQuery("select count(a) from FileAsset a" + where, Long.class)
                        .setParameter("owner", actor.id())
                        .getSingleResult();
        return new PageResult<>(
                rows.stream().map(this::adminView).toList(), page.page(), page.pageSize(), total);
    }

    @Transactional(readOnly = true)
    public AssetDtos.AdminView adminGet(long id) {
        return adminView(manageable(id, false));
    }

    @Transactional
    public AssetDtos.AdminView disable(long id, AssetDtos.Disable request) {
        var a = manageable(id, true);
        ContentSupport.version(a.getVersion(), request.version());
        long before = a.getVersion();
        a.status = "DISABLED";
        em.flush();
        support.audit("ASSET_DISABLE", "FILE_ASSET", id, before, a.getVersion(), request.reason());
        return adminView(a);
    }

    private FileAsset manageable(long id, boolean lock) {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        var a =
                lock
                        ? em.find(FileAsset.class, id, LockModeType.PESSIMISTIC_WRITE)
                        : em.find(FileAsset.class, id);
        if (a == null
                || !("ADMIN".equals(a.ownerType) && a.ownerId.equals(actor.id())
                        || "SYSTEM".equals(a.ownerType) && a.seedKey != null))
            throw ContentSupport.missing();
        return a;
    }

    private AssetDtos.AdminView adminView(FileAsset a) {
        return new AssetDtos.AdminView(
                a.getId().toString(),
                a.getVersion(),
                a.originalName,
                a.extension,
                a.mime,
                a.bytes,
                a.sha256,
                a.purpose,
                a.status,
                a.scanStatus,
                publicUrl(a.getId()));
    }

    @Transactional(readOnly = true)
    public AssetDtos.Summary readySummary(long id, String purpose) {
        var a = ContentSupport.require(em, FileAsset.class, id);
        return a.status.equals("READY") && a.purpose.equals(purpose) ? summary(a) : null;
    }

    private void audit(FileAsset a, String action, long before) {
        support.audit(
                action,
                "FILE_ASSET",
                a.getId(),
                before,
                before < 0 ? 0 : before + 1,
                "Content asset upload");
    }

    @Transactional(readOnly = true)
    public AssetDtos.Summary requirePurpose(long id, String purpose, boolean owner) {
        var a = ContentSupport.require(em, FileAsset.class, id);
        if (!a.status.equals("READY") || !a.purpose.equals(purpose))
            throw ContentSupport.invalid("Ready asset with the required purpose is required");
        if (owner) {
            var actor = CurrentActor.require();
            boolean approvedSeed =
                    "SYSTEM".equals(a.ownerType)
                            && a.seedKey != null
                            && actor.type() == CurrentActor.Type.ADMIN
                            && actor.permissions().contains("CONTENT_EDIT");
            if (!approvedSeed
                    && (!a.ownerType.equals(actor.type().name()) || !a.ownerId.equals(actor.id())))
                throw new ApiException(
                        HttpStatus.FORBIDDEN, "FORBIDDEN", "Asset belongs to another actor");
        }
        return summary(a);
    }

    @Transactional(readOnly = true)
    public String publicUrl(Long id) {
        if (id == null) return null;
        var a = em.find(FileAsset.class, id);
        return a != null && a.status.equals("READY") && a.purpose.equals("PUBLIC_IMAGE")
                ? "/api/v1/assets/" + id + "/content"
                : null;
    }

    public PublicBytes publicBytes(long id) {
        var object =
                tx.execute(
                        status -> {
                            var a = ContentSupport.require(em, FileAsset.class, id);
                            if (!a.purpose.equals("PUBLIC_IMAGE")) throw ContentSupport.missing();
                            if (!a.status.equals("READY"))
                                throw new ApiException(
                                        HttpStatus.CONFLICT,
                                        "FILE_NOT_READY",
                                        "Asset is not ready");
                            return new ProtectedObject(
                                    a.mime,
                                    new StorageProvider.SealedObject(
                                            a.storageKey, a.sealedVersion, a.bytes));
                        });
        return openProtected(object);
    }

    /** Internal capability, returned only after a domain relation has authorized access. */
    public record ProtectedObject(String mime, StorageProvider.SealedObject sealed) {}

    @Transactional(readOnly = true)
    public AssetDtos.Summary requireCustomerEvidence(long id, long ownerId) {
        var a = ContentSupport.require(em, FileAsset.class, id);
        if (!"READY".equals(a.status)
                || !"AFTERSALE_EVIDENCE".equals(a.purpose)
                || !"CUSTOMER".equals(a.ownerType)
                || a.ownerId != ownerId) throw ContentSupport.missing();
        return summary(a);
    }

    @Transactional(readOnly = true)
    public ProtectedObject protectedObject(long id, String purpose, long ownerId) {
        var a = ContentSupport.require(em, FileAsset.class, id);
        if (!"READY".equals(a.status)
                || !purpose.equals(a.purpose)
                || !"CUSTOMER".equals(a.ownerType)
                || a.ownerId != ownerId) throw ContentSupport.missing();
        return new ProtectedObject(
                a.mime, new StorageProvider.SealedObject(a.storageKey, a.sealedVersion, a.bytes));
    }

    public PublicBytes openProtected(ProtectedObject object) {
        return openProtected(object, 0, object.sealed().bytes() - 1);
    }

    public PublicBytes openProtected(ProtectedObject object, long start, long end) {
        try {
            return new PublicBytes(
                    object.mime(), end - start + 1, storage.open(object.sealed(), start, end));
        } catch (IOException e) {
            throw StorageConfiguration.unavailable();
        }
    }

    public record PublicBytes(String mime, long bytes, InputStream stream) {}

    private AssetDtos.Summary summary(FileAsset a) {
        return new AssetDtos.Summary(
                a.getId().toString(), a.originalName, a.extension, a.mime, a.bytes);
    }

    private AssetDtos.View view(FileAsset a) {
        return new AssetDtos.View(
                a.getId().toString(),
                a.originalName,
                a.extension,
                a.mime,
                a.bytes,
                a.sha256,
                a.purpose,
                a.status,
                a.scanStatus,
                publicUrl(a.getId()));
    }

    private static String hash(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
