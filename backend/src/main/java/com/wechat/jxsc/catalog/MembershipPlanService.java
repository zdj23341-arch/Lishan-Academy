package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.*;
import com.wechat.jxsc.common.content.ContentSupport;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MembershipPlanService {
    @PersistenceContext private EntityManager em;
    private final ContentSupport support;
    private final CatalogConsistencyGuard guard;

    public MembershipPlanService(ContentSupport support, CatalogConsistencyGuard guard) {
        this.support = support;
        this.guard = guard;
    }

    public record PlanView(
            String id,
            String productId,
            long version,
            String level,
            int durationMonths,
            long priceFen,
            String status) {}

    public record PlanEdit(
            @NotNull @PositiveOrZero Long version,
            @Min(6) @Max(12) int durationMonths,
            @NotBlank String status) {}

    @Transactional
    public PlanView edit(long id, PlanEdit r) {
        guard.acquire();
        var plan = guard.current(MembershipPlan.class, id);
        ContentSupport.version(plan.getVersion(), r.version());
        var product = guard.current(Product.class, plan.productId);
        if (!Set.of("ENABLED", "DISABLED").contains(r.status()))
            throw ContentSupport.invalid("Invalid plan status");
        validateMonths(r.durationMonths());
        if (r.status().equals("ENABLED")) ensureCycleFree(r.durationMonths(), plan.getId());
        if (product.status.equals("ON_SALE")
                && (r.durationMonths() != plan.durationMonths || !r.status().equals("ENABLED")))
            throw ContentSupport.invalid("Unpublish before changing plan terms");
        long before = plan.getVersion();
        plan.durationMonths = r.durationMonths();
        plan.status = r.status();
        em.flush();
        support.audit(
                "MEMBERSHIP_PLAN_EDIT",
                "MEMBERSHIP_PLAN",
                id,
                before,
                plan.getVersion(),
                "Edit membership plan");
        return view(plan);
    }

    void ensureCycleFree(int months, Long except) {
        if (guard.enabledDurationExists(months, except))
            throw ContentSupport.invalid("Only one enabled plan per duration");
    }

    static void validateMonths(int months) {
        if (months != 6 && months != 12)
            throw ContentSupport.invalid("Membership duration is 6 or 12 months");
    }

    @Transactional(readOnly = true)
    public PageResult<PlanView> list(PageQuery page) {
        var all =
                em.createQuery(
                                "select m from MembershipPlan m,Product p where p.id=m.productId"
                                        + " and p.status='ON_SALE' and m.status='ENABLED' order by"
                                        + " m.durationMonths,m.id",
                                MembershipPlan.class)
                        .getResultList();
        return new PageResult<>(
                all.stream().skip(page.offset()).limit(page.pageSize()).map(this::view).toList(),
                page.page(),
                page.pageSize(),
                all.size());
    }

    private PlanView view(MembershipPlan m) {
        var p = ContentSupport.require(em, Product.class, m.productId);
        return new PlanView(
                m.getId().toString(),
                p.getId().toString(),
                m.getVersion(),
                m.levelCode,
                m.durationMonths,
                p.priceFen,
                m.status);
    }

    @Transactional(readOnly = true)
    public PageResult<PlanView> adminList(PageQuery page) {
        var rows =
                em.createQuery(
                                "select m from MembershipPlan m order by m.durationMonths,m.id",
                                MembershipPlan.class)
                        .setFirstResult(page.offset())
                        .setMaxResults(page.pageSize())
                        .getResultList();
        return new PageResult<>(
                rows.stream().map(this::view).toList(),
                page.page(),
                page.pageSize(),
                em.createQuery("select count(m) from MembershipPlan m", Long.class)
                        .getSingleResult());
    }
}
