package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.jxsc.auth.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.order.AttributionResolver;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReferralService implements AttributionResolver {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    private final FinanceStore f;
    private final CommerceSupport s;
    private final DistributionAdministration admin;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ReferralCodeImages images;

    public ReferralService(
            FinanceStore f,
            CommerceSupport s,
            DistributionAdministration admin,
            org.springframework.transaction.PlatformTransactionManager manager,
            ReferralCodeImages images) {
        this.f = f;
        this.s = s;
        this.admin = admin;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.images = images;
    }

    private Map<String, Object> valid(long product, long distributor) {
        f.one("select id from product where id=? and status='ON_SALE' for update", product);
        var d = f.one("select * from distributor where id=? for update", distributor);
        if (!str(d, "status").equals("ACTIVE"))
            throw CommerceSupport.invalid("Distributor disabled");
        var rules =
                f.db.queryForList(
                        "select * from product_distributor_rule where product_id=? and"
                                + " distributor_id=? and enabled=true and effective_at<=? and"
                                + " (expires_at is null or expires_at>?) for update",
                        product,
                        distributor,
                        f.now(),
                        f.now());
        if (rules.isEmpty()) throw CommerceSupport.invalid("No effective distribution rule");
        return rules.getFirst();
    }

    public FinanceDtos.CodeView code(FinanceDtos.CodeRequest r) {
        var prepared = tx.execute(status -> prepareCode(r));
        String path =
                "/pages/product/detail?id=" + prepared.productId() + "&scene=" + prepared.code();
        String image = images.ensure(prepared, path.substring(1));
        // Recheck eligibility after remote I/O, before handing out a cached URL.
        tx.executeWithoutResult(status -> eligible(prepared.code()));
        return new FinanceDtos.CodeView(prepared.code(), path, image);
    }

    private ReferralCodeImages.Prepared prepareCode(FinanceDtos.CodeRequest r) {
        lifecycle.customer();
        long p = CommerceSupport.id(r.productId()), d = n(admin.own(), "id");
        valid(p, d);
        var existing =
                f.db.queryForList(
                        "select * from referral_code where product_id=? and distributor_id=? and"
                            + " enabled=true and expires_at>? order by id desc limit 1 for update",
                        p,
                        d,
                        f.now());
        String code;
        if (!existing.isEmpty()) code = str(existing.getFirst(), "code");
        else {
            code = token();
            f.db.update(
                    "insert into"
                        + " referral_code(code,product_id,distributor_id,enabled,expires_at,created_at)"
                        + " values(?,?,?,true,?,?)",
                    code,
                    p,
                    d,
                    ts(f.clock.instant().plusSeconds(30 * 86400L)),
                    f.now());
        }
        return images.prepare(f.one("select * from referral_code where code=? for update", code));
    }

    private Map<String, Object> eligible(String code) {
        var row =
                f.one(
                        "select * from referral_code where code=? and enabled=true and"
                                + " expires_at>?",
                        code,
                        f.now());
        valid(n(row, "product_id"), n(row, "distributor_id"));
        return row;
    }

    public byte[] image(String code) {
        if (!code.matches("[A-Za-z0-9_-]{32}")) throw CommerceSupport.missing();
        var row = tx.execute(status -> eligible(code));
        return images.read(row);
    }

    @Transactional
    public FinanceDtos.TouchView resolve(FinanceDtos.ResolveRequest r) {
        Long user = null;
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentActor actor) {
            actor.requireType(CurrentActor.Type.CUSTOMER);
            user = actor.id();
            lifecycle.requireActive(user);
            f.one("select id from user_account where id=? for update", user);
        }
        var c =
                f.one(
                        "select * from referral_code where code=? and enabled=true and"
                                + " expires_at>?",
                        r.code(),
                        f.now());
        long p = n(c, "product_id");
        valid(p, n(c, "distributor_id"));
        String token = token();
        var expiry = f.clock.instant().plusSeconds(30 * 86400L);
        f.db.update(
                "insert into"
                    + " referral_touch(code_id,product_id,user_id,visitor_key_hash,token_hash,touched_at,expires_at)"
                    + " values(?,?,?,?,?,?,?)",
                n(c, "id"),
                p,
                user,
                r.visitorKey() == null ? null : TokenCodec.hash(r.visitorKey()),
                TokenCodec.hash(token),
                f.now(),
                ts(expiry));
        return new FinanceDtos.TouchView("" + p, token, expiry);
    }

    @Transactional
    public FinanceDtos.TouchView claim(FinanceDtos.ClaimRequest r) {
        lifecycle.customer();
        long user = CurrentActor.require().id();
        CurrentActor.require().requireType(CurrentActor.Type.CUSTOMER);
        f.one("select id from user_account where id=? for update", user);
        var t =
                f.one(
                        "select * from referral_touch where token_hash=? for update",
                        TokenCodec.hash(r.attributionToken()));
        if (!time(t, "expires_at").isAfter(f.clock.instant())
                || (t.get("user_id") != null && n(t, "user_id") != user))
            throw CommerceSupport.invalid("Invalid attribution token");
        f.db.update("update referral_touch set user_id=? where id=?", user, n(t, "id"));
        return new FinanceDtos.TouchView(
                "" + n(t, "product_id"), r.attributionToken(), time(t, "expires_at"));
    }

    @Transactional
    public JsonNode resolveAndLock(long user, long product, String token, long order, long goods) {
        if (token != null && !token.isBlank()) {
            var supplied =
                    f.db.queryForList(
                            "select * from referral_touch where token_hash=?",
                            TokenCodec.hash(token));
            if (supplied.isEmpty()) throw CommerceSupport.invalid("Invalid attribution token");
            var t = supplied.getFirst();
            if (t.get("user_id") == null
                    || n(t, "user_id") != user
                    || n(t, "product_id") != product
                    || !time(t, "expires_at").isAfter(f.clock.instant()))
                throw CommerceSupport.invalid("Invalid attribution token");
        }
        var touches =
                f.db.queryForList(
                        "select t.*,c.distributor_id from referral_touch t join referral_code c on"
                                + " c.id=t.code_id where t.user_id=? and t.product_id=? and"
                                + " t.expires_at>? order by t.touched_at desc,t.id desc",
                        user,
                        product,
                        f.now());
        for (var t : touches) {
            long did = n(t, "distributor_id");
            Map<String, Object> r;
            try {
                r = valid(product, did);
            } catch (com.wechat.jxsc.common.ApiException invalid) {
                continue;
            }
            var d = f.one("select * from distributor where id=? for update", did);
            if (n(d, "user_id") == user) return s.node(Map.of());
            int days = admin.settings().observationDays();
            f.db.update(
                    "insert into"
                        + " attribution(order_id,distributor_id,referral_touch_id,rate_bps,rule_version,base_fen,observation_days,bound_at)"
                        + " values(?,?,?,?,?,?,?,?)",
                    order,
                    did,
                    n(t, "id"),
                    n(r, "rate_bps"),
                    n(r, "rule_version"),
                    goods,
                    days,
                    f.now());
            return s.node(
                    Map.of(
                            "distributorId",
                            "" + did,
                            "referralTouchId",
                            "" + n(t, "id"),
                            "rateBps",
                            n(r, "rate_bps"),
                            "ruleVersion",
                            n(r, "rule_version"),
                            "baseFen",
                            goods,
                            "observationDays",
                            days,
                            "strategyVersion",
                            1));
        }
        return s.node(Map.of());
    }
}
