package com.wechat.jxsc.membership;

import com.wechat.jxsc.catalog.MembershipPlanService;
import com.wechat.jxsc.common.PageQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class MembershipViewService {
    private final EntitlementAccess access;
    private final EntitlementLedger ledger;
    private final MembershipPlanService plans;
    private final Clock clock;

    public MembershipViewService(
            EntitlementAccess access,
            EntitlementLedger ledger,
            MembershipPlanService plans,
            Clock clock) {
        this.access = access;
        this.ledger = ledger;
        this.plans = plans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MembershipDtos.View view(long user) {
        var now = clock.instant();
        var right = access.membership(user);
        var grants = ledger.membershipGrants(user);
        var state =
                switch (right.status()) {
                    case "ACTIVE" -> MembershipDtos.State.ACTIVE;
                    case "EXPIRED" -> MembershipDtos.State.EXPIRED;
                    case "PENDING" ->
                            MembershipReplay.replay(grants).stream()
                                            .anyMatch(
                                                    interval -> !interval.expiresAt().isAfter(now))
                                    ? MembershipDtos.State.EXPIRED
                                    : MembershipDtos.State.NONE;
                    default -> MembershipDtos.State.NONE;
                };
        // Catalog permits at most one enabled STANDARD offer for each 6/12-month duration.
        var offers =
                plans.list(new PageQuery(1, 100)).items().stream()
                        .filter(
                                plan ->
                                        plan.level().equals("STANDARD")
                                                && (plan.durationMonths() == 6
                                                        || plan.durationMonths() == 12))
                        .map(
                                plan ->
                                        new MembershipDtos.PlanOffer(
                                                plan.productId(),
                                                plan.durationMonths(),
                                                plan.priceFen(),
                                                MembershipReplay.projectedExpiry(
                                                        grants, now, plan.durationMonths())))
                        .toList();
        return new MembershipDtos.View(
                "STANDARD",
                state,
                right.status(),
                right.startsAt(),
                right.expiresAt(),
                right.active(),
                offers);
    }
}
