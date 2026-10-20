package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.storage.StorageProvider;
import com.wechat.jxsc.payment.wechat.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.util.*;

@Service
public class ReferralCodeImages {
    private final FinanceStore f;
    private final StorageProvider storage;
    private final WechatMiniProgramCodeClient client;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String appId;

    public ReferralCodeImages(
            FinanceStore f,
            StorageProvider storage,
            WechatMiniProgramCodeClient client,
            PlatformTransactionManager manager,
            Environment env,
            WechatSettings settings) {
        this.f = f;
        this.storage = storage;
        this.client = client;
        tx = new TransactionTemplate(manager);
        enabled =
                "REAL".equals(env.getProperty("jxsc.providers.identity", "REAL"))
                        || env.getProperty("jxsc.wechat.share.enabled", Boolean.class, false);
        appId = settings.appId();
    }

    record Prepared(String code, long productId, String lease, boolean cached) {}

    /** Called only after authorization and with the referral row locked. */
    Prepared prepare(Map<String, Object> row) {
        String code = str(row, "code");
        long product = n(row, "product_id");
        boolean cached = row.get("image_key") != null && appId.equals(row.get("image_app_id"));
        if (!enabled || cached) return new Prepared(code, product, null, cached);
        var until = time(row, "image_lease_until");
        if (until != null && until.isAfter(f.clock.instant()))
            throw WechatProtocol.unavailable("WECHAT_SHARE_GENERATING");
        String lease = token();
        f.db.update(
                "update referral_code set image_lease_token=?,image_lease_until=? where code=?",
                lease,
                ts(f.clock.instant().plusSeconds(120)),
                code);
        return new Prepared(code, product, lease, false);
    }

    String ensure(Prepared prepared, String path) {
        if (!enabled) return null;
        if (!prepared.cached()) {
            try {
                byte[] png = client.generate(path);
                if (png.length < 1 || png.length > 1048576)
                    throw new IOException("Image length invalid");
                String key = UUID.randomUUID().toString().replace("-", "");
                storage.writeOnce(key, new ByteArrayInputStream(png), png.length);
                var object = storage.seal(key);
                if (object.bytes() != png.length)
                    throw new IOException("Image storage length mismatch");
                int stored =
                        tx.execute(
                                status ->
                                        f.db.update(
                                                "update referral_code set"
                                                    + " image_key=?,image_version=?,image_bytes=?,image_app_id=?,image_lease_token=null,image_lease_until=null"
                                                    + " where code=? and image_lease_token=? and"
                                                    + " enabled=true and expires_at>?",
                                                object.key(),
                                                object.version(),
                                                object.bytes(),
                                                appId,
                                                prepared.code(),
                                                prepared.lease(),
                                                f.now()));
                if (stored != 1) throw WechatProtocol.unavailable("WECHAT_SHARE_RETRY");
            } catch (IOException e) {
                throw WechatProtocol.unavailable("WECHAT_SHARE_STORAGE_UNAVAILABLE");
            } finally {
                tx.executeWithoutResult(
                        status ->
                                f.db.update(
                                        "update referral_code set"
                                            + " image_lease_token=null,image_lease_until=null where"
                                            + " code=? and image_lease_token=?",
                                        prepared.code(),
                                        prepared.lease()));
            }
        }
        return "/api/v1/referrals/" + prepared.code() + "/image";
    }

    byte[] read(Map<String, Object> row) {
        if (!enabled || row.get("image_key") == null || !appId.equals(row.get("image_app_id")))
            throw CommerceSupport.missing();
        var object =
                new StorageProvider.SealedObject(
                        str(row, "image_key"), str(row, "image_version"), n(row, "image_bytes"));
        try (var stream = storage.open(object)) {
            var data = stream.readNBytes((int) object.bytes() + 1);
            if (data.length != object.bytes())
                throw new IOException("Image storage length mismatch");
            return data;
        } catch (IOException e) {
            throw WechatProtocol.unavailable("WECHAT_SHARE_STORAGE_UNAVAILABLE");
        }
    }
}
