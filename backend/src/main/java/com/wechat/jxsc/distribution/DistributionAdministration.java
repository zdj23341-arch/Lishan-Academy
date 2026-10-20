package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.CommerceSupport;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class DistributionAdministration {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.auth.AccountLifecycle lifecycle;

    private final FinanceStore f;
    private final CommerceSupport s;
    private final Environment env;

    public DistributionAdministration(FinanceStore f, CommerceSupport s, Environment env) {
        this.f = f;
        this.s = s;
        this.env = env;
    }

    public String appId() {
        return "DEV".equals(env.getProperty("jxsc.providers.transfer"))
                ? "DEV"
                : env.getProperty("jxsc.wechat.app-id", "");
    }

    @Transactional
    public FinanceDtos.DistributorView create(FinanceDtos.CreateDistributor r) {
        long user = CommerceSupport.id(r.userId());
        lifecycle.requireActive(user);
        f.one("select id from user_account where id=? and status='ACTIVE' for update", user);
        var existing = f.db.queryForList("select * from distributor where user_id=?", user);
        if (!existing.isEmpty()) return distributor(existing.getFirst());
        var ids =
                f.db.queryForList(
                        "select * from user_wechat_identity where user_id=? and app_id=? for"
                                + " update",
                        user,
                        appId());
        Long identity = ids.size() == 1 ? n(ids.getFirst(), "id") : null;
        if (identity != null)
            f.db.update(
                    "insert ignore into distribution_identity(identity_id,user_id) values(?,?)",
                    identity,
                    user);
        boolean ready = identity != null && appId().equals("DEV");
        long id =
                f.insert(
                        "insert into"
                            + " distributor(user_id,recipient_identity_id,reason,receipt_ready,approved_at,created_at,updated_at)"
                            + " values(?,?,?,?,?,?,?)",
                        user,
                        identity,
                        r.reason(),
                        ready,
                        f.now(),
                        f.now(),
                        f.now());
        f.db.update(
                "insert into commission_account(distributor_id,created_at,updated_at)"
                        + " values(?,?,?)",
                id,
                f.now(),
                f.now());
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentActor)
            s.audit(
                    "DISTRIBUTOR_CREATE",
                    "DISTRIBUTOR",
                    id,
                    r.reason(),
                    Map.of("userId", r.userId(), "receiptReady", ready));
        else
            s.systemAudit(
                    "DISTRIBUTOR_CREATE",
                    "DISTRIBUTOR",
                    id,
                    r.reason(),
                    Map.of("userId", r.userId(), "receiptReady", ready));
        return distributor(f.one("select * from distributor where id=?", id));
    }

    @Transactional
    public FinanceDtos.DistributorView edit(long id, FinanceDtos.EditDistributor r) {
        long user = n(f.one("select user_id from distributor where id=?", id), "user_id");
        lifecycle.requireActive(user);
        var d = f.one("select * from distributor where id=? for update", id);
        if (n(d, "version") != r.version())
            throw CommerceSupport.conflict("VERSION_CONFLICT", "Distributor changed");
        f.db.update(
                "update distributor set"
                    + " status=?,risk_blocked=?,reason=?,version=version+1,updated_at=? where id=?",
                r.status(),
                r.riskBlocked(),
                r.reason(),
                f.now(),
                id);
        var after = distributor(f.one("select * from distributor where id=? for update", id));
        s.audit("DISTRIBUTOR_EDIT", "DISTRIBUTOR", id, r.reason(), distributor(d), after);
        return after;
    }

    public FinanceDtos.DistributorView distributor(Map<String, Object> d) {
        return new FinanceDtos.DistributorView(
                "" + n(d, "id"),
                "" + n(d, "user_id"),
                str(d, "status"),
                bool(d, "risk_blocked"),
                bool(d, "receipt_ready"),
                str(d, "reason"),
                n(d, "version"));
    }

    public Map<String, Object> own() {
        CurrentActor actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        var rows = f.db.queryForList("select * from distributor where user_id=?", actor.id());
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "DISTRIBUTOR_NOT_ENABLED",
                    "当前账号尚未开通分销资格");
        return rows.getFirst();
    }

    public PageResult<FinanceDtos.DistributorView> list(PageQuery q) {
        return new PageResult<>(
                f
                        .db
                        .queryForList(
                                "select * from distributor order by id desc limit ? offset ?",
                                q.pageSize(),
                                q.offset())
                        .stream()
                        .map(this::distributor)
                        .toList(),
                q.page(),
                q.pageSize(),
                f.db.queryForObject("select count(*) from distributor", Long.class));
    }

    @Transactional
    public FinanceDtos.RulesView rules(long product) {
        return rules(product, false);
    }

    private FinanceDtos.RulesView rules(long product, boolean current) {
        String lock = current ? " for update" : "";
        var row =
                f.db.queryForList(
                        "select version from distribution_rule_set where product_id=?" + lock,
                        product);
        return new FinanceDtos.RulesView(
                row.isEmpty() ? 0 : n(row.getFirst(), "version"),
                f
                        .db
                        .queryForList(
                                "select * from product_distributor_rule where product_id=? order by"
                                        + " distributor_id"
                                        + lock,
                                product)
                        .stream()
                        .map(
                                r ->
                                        new FinanceDtos.RuleView(
                                                "" + n(r, "distributor_id"),
                                                (int) n(r, "rate_bps"),
                                                bool(r, "enabled"),
                                                time(r, "effective_at"),
                                                time(r, "expires_at"),
                                                n(r, "rule_version")))
                        .toList());
    }

    @Transactional
    public FinanceDtos.RulesView rules(long product, FinanceDtos.RulesEdit r) {
        f.one("select id from product where id=? for update", product);
        f.db.update(
                "insert into distribution_rule_set(product_id) values(?) on duplicate key update"
                        + " product_id=product_id",
                product);
        var set =
                f.one("select * from distribution_rule_set where product_id=? for update", product);
        if (n(set, "version") != r.version())
            throw CommerceSupport.conflict("VERSION_CONFLICT", "Rules changed");
        Set<Long> seen = new HashSet<>();
        var requested =
                r.rules().stream()
                        .sorted(
                                Comparator.comparingLong(
                                        v -> CommerceSupport.id(v.distributorId())))
                        .toList();
        // Acquire every requested distributor before locking rule rows; no distributor lock
        // may be acquired behind the before-snapshot's current rule locks.
        for (var rule : requested) {
            long did = CommerceSupport.id(rule.distributorId());
            if (!seen.add(did)) throw CommerceSupport.invalid("Duplicate distributor");
            f.one("select id from distributor where id=? for update", did);
            Instant start = rule.effectiveAt() == null ? f.clock.instant() : rule.effectiveAt();
            if (rule.expiresAt() != null && !rule.expiresAt().isAfter(start))
                throw CommerceSupport.invalid("Invalid rule dates");
        }
        var before = rules(product, true);
        for (var rule : requested) {
            long did = CommerceSupport.id(rule.distributorId());
            Instant start = rule.effectiveAt() == null ? f.clock.instant() : rule.effectiveAt();
            f.db.update(
                    "insert into"
                        + " product_distributor_rule(product_id,distributor_id,rate_bps,enabled,effective_at,expires_at,rule_version,created_at,updated_at)"
                        + " values(?,?,?,?,?,?,?,?,?) on duplicate key update"
                        + " rate_bps=values(rate_bps),enabled=values(enabled),effective_at=values(effective_at),expires_at=values(expires_at),rule_version=values(rule_version),updated_at=values(updated_at)",
                    product,
                    did,
                    rule.rateBps(),
                    rule.enabled(),
                    ts(start),
                    ts(rule.expiresAt()),
                    r.version() + 1,
                    f.now(),
                    f.now());
        }
        for (var old : before.rules())
            if (!seen.contains(CommerceSupport.id(old.distributorId())))
                f.db.update(
                        "update product_distributor_rule set"
                            + " enabled=false,rule_version=?,updated_at=? where product_id=? and"
                            + " distributor_id=?",
                        r.version() + 1,
                        f.now(),
                        product,
                        CommerceSupport.id(old.distributorId()));
        f.db.update(
                "update distribution_rule_set set version=version+1 where product_id=?", product);
        var after = rules(product, true);
        s.audit("DISTRIBUTION_RULES", "PRODUCT", product, r.reason(), before, after);
        return after;
    }

    public FinanceDtos.SettingsView settings() {
        return settings(f.one("select * from finance_settings where id=1"));
    }

    private FinanceDtos.SettingsView settings(Map<String, Object> r) {
        return new FinanceDtos.SettingsView(
                n(r, "version"),
                new FinanceDtos.Limits(
                        n(r, "min_fen"), n(r, "max_fen"), (int) n(r, "daily_max_count")),
                (int) n(r, "observation_days"));
    }

    @Transactional
    public FinanceDtos.SettingsView settings(FinanceDtos.SettingsEdit r) {
        Set<String> keys = Set.of("minFen", "maxFen", "dailyMaxCount", "observationDays");
        if (!keys.containsAll(r.values().keySet())
                || r.values().values().stream().anyMatch(Objects::isNull))
            throw CommerceSupport.invalid("Unknown operational setting");
        var old = f.one("select * from finance_settings where id=1 for update");
        if (n(old, "version") != r.version())
            throw CommerceSupport.conflict("VERSION_CONFLICT", "Settings changed");
        long min = r.values().getOrDefault("minFen", n(old, "min_fen")),
                max = r.values().getOrDefault("maxFen", n(old, "max_fen")),
                count = r.values().getOrDefault("dailyMaxCount", n(old, "daily_max_count")),
                days = r.values().getOrDefault("observationDays", n(old, "observation_days"));
        if (min < 100
                || max > 100000
                || max < min
                || count < 1
                || count > 5
                || days < 7
                || days > 90)
            throw CommerceSupport.invalid("Settings exceed configured merchant limits");
        f.db.update(
                "update finance_settings set"
                    + " min_fen=?,max_fen=?,daily_max_count=?,observation_days=?,version=version+1"
                    + " where id=1",
                min,
                max,
                count,
                days);
        var after = settings(f.one("select * from finance_settings where id=1 for update"));
        s.audit("SETTINGS_EDIT", "FINANCE_SETTINGS", 1, r.reason(), settings(old), after);
        return after;
    }
}
