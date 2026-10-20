package com.wechat.jxsc.distribution.wechat;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.distribution.TransferProvider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WechatReceiptProfile {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    public record View(boolean ready, boolean nameProvided) {}

    private final JdbcTemplate db;
    private final SensitiveCipher cipher;
    private final TransferProvider provider;
    private final CommerceSupport support;

    public WechatReceiptProfile(
            JdbcTemplate db,
            SensitiveCipher cipher,
            TransferProvider provider,
            CommerceSupport support) {
        this.db = db;
        this.cipher = cipher;
        this.provider = provider;
        this.support = support;
    }

    @Transactional
    public View bind(String name) {
        lifecycle.customer();
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        var distributor =
                db.queryForList(
                        "select status from distributor where user_id=?", actor.id());
        if (distributor.isEmpty())
            throw new ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "DISTRIBUTOR_NOT_ENABLED",
                    "当前账号尚未开通分销资格");
        if (!"ACTIVE".equals(distributor.getFirst().get("status")))
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "DISTRIBUTOR_DISABLED",
                    "当前分销资格已停用");
        if (!(provider instanceof WechatTransferProvider) || !provider.ready())
            throw com.wechat.jxsc.payment.wechat.WechatProtocol.unavailable("WITHDRAWAL_NOT_READY");
        db.queryForObject(
                "select id from user_account where id=? for update", Long.class, actor.id());
        var identities =
                db.queryForList(
                        "select i.id from user_wechat_identity i join distribution_identity a on"
                            + " a.identity_id=i.id and a.user_id=i.user_id where i.user_id=? and"
                            + " i.app_id=? for update",
                        Long.class,
                        actor.id(),
                        provider.appId());
        if (identities.size() != 1)
            throw com.wechat.jxsc.payment.wechat.WechatProtocol.unavailable(
                    "WECHAT_RECIPIENT_NOT_BOUND");
        var rows =
                db.queryForList(
                        "select id from distributor where user_id=? and status='ACTIVE' for update",
                        Long.class,
                        actor.id());
        if (rows.size() != 1) throw CommerceSupport.missing();
        String value = name == null || name.isBlank() ? null : name.trim();
        db.update(
                "update distributor set"
                    + " recipient_identity_id=?,recipient_name_cipher=?,receipt_ready=true,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                    + " where id=?",
                identities.getFirst(),
                cipher.encrypt(value),
                rows.getFirst());
        support.audit(
                "RECEIPT_PROFILE_BOUND",
                "DISTRIBUTOR",
                rows.getFirst(),
                "Authenticated customer bound own WeChat receipt identity",
                java.util.Map.of("ready", true, "nameProvided", value != null));
        return new View(true, value != null);
    }
}
