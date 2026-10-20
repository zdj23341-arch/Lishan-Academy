package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.commerce.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ShippingRuleService {
    @PersistenceContext private EntityManager em;
    private final CommerceSupport support;

    public ShippingRuleService(CommerceSupport support) {
        this.support = support;
    }

    @Transactional
    public FulfillmentDtos.RuleView save(String region, FulfillmentDtos.RuleWrite r) {
        if (!region.matches("[0-9]{6}")) throw CommerceSupport.invalid("Invalid province code");
        em.createNativeQuery(
                        "INSERT INTO"
                            + " shipping_rule(province_code,enabled,freight_fen,free_threshold_fen,created_at,updated_at,version)"
                            + " VALUES (:p,false,0,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0) ON"
                            + " DUPLICATE KEY UPDATE id=id")
                .setParameter("p", region)
                .executeUpdate();
        var s =
                em.createQuery(
                                "select s from ShippingRule s where s.provinceCode=:p",
                                ShippingRule.class)
                        .setParameter("p", region)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getSingleResult();
        em.refresh(s, LockModeType.PESSIMISTIC_WRITE);
        if (s.enabled == r.enabled()
                && s.freightFen == r.freightFen()
                && Objects.equals(s.freeThresholdFen, r.freeThresholdFen())) return view(s);
        if (s.getVersion() != r.version())
            throw CommerceSupport.conflict("VERSION_CONFLICT", "Reload shipping rule");
        s.enabled = r.enabled();
        s.freightFen = r.freightFen();
        s.freeThresholdFen = r.freeThresholdFen();
        em.flush();
        support.audit(
                "SHIPPING_RULE_EDIT",
                "SHIPPING_RULE",
                s.getId(),
                r.reason(),
                Map.of("provinceCode", region, "version", s.getVersion()));
        return view(s);
    }

    public ShippingRule match(String province) {
        var rows =
                em.createQuery(
                                "select s from ShippingRule s where s.provinceCode=:p",
                                ShippingRule.class)
                        .setParameter("p", province)
                        .setLockMode(LockModeType.PESSIMISTIC_READ)
                        .getResultList();
        if (rows.isEmpty() || !rows.getFirst().enabled)
            throw CommerceSupport.conflict(
                    "SHIPPING_UNAVAILABLE", "No enabled shipping rule for region");
        var s = rows.getFirst();
        em.refresh(s, LockModeType.PESSIMISTIC_READ);
        if (!s.enabled)
            throw CommerceSupport.conflict(
                    "SHIPPING_UNAVAILABLE", "No enabled shipping rule for region");
        return s;
    }

    public long freight(ShippingRule r, long goods) {
        return r.freeThresholdFen != null && goods >= r.freeThresholdFen ? 0 : r.freightFen;
    }

    @Transactional(readOnly = true)
    public PageResult<FulfillmentDtos.RuleView> list(PageQuery p) {
        var rows =
                em.createQuery(
                                "select s from ShippingRule s order by s.provinceCode",
                                ShippingRule.class)
                        .setFirstResult(p.offset())
                        .setMaxResults(p.pageSize())
                        .getResultList();
        return new PageResult<>(
                rows.stream().map(this::view).toList(),
                p.page(),
                p.pageSize(),
                em.createQuery("select count(s) from ShippingRule s", Long.class)
                        .getSingleResult());
    }

    private FulfillmentDtos.RuleView view(ShippingRule s) {
        return new FulfillmentDtos.RuleView(
                s.getId().toString(),
                s.getVersion(),
                s.provinceCode,
                s.enabled,
                s.freightFen,
                s.freeThresholdFen);
    }
}
