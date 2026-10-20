package com.wechat.jxsc.membership;

import java.time.*;
import java.util.*;

/** Pure, shared by delivery, full-refund projection and read-only refund preview. */
public final class MembershipReplay {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public record Grant(long id, Instant paidAt, int months) {}

    public record Interval(long grantId, Instant startsAt, Instant expiresAt) {}

    public static Instant plusMonths(Instant from, int months) {
        if (months != 6 && months != 12)
            throw new IllegalArgumentException("Unsupported member term");
        return from.atZone(BUSINESS_ZONE).plusMonths(months).toInstant();
    }

    public static List<Interval> replay(List<Grant> grants) {
        var sorted =
                grants.stream()
                        .sorted(Comparator.comparing(Grant::paidAt).thenComparingLong(Grant::id))
                        .toList();
        List<Interval> result = new ArrayList<>();
        Instant end = null;
        for (var grant : sorted) {
            Instant start = end == null || grant.paidAt().isAfter(end) ? grant.paidAt() : end;
            end = plusMonths(start, grant.months());
            result.add(new Interval(grant.id(), start, end));
        }
        return List.copyOf(result);
    }

    /**
     * Hypothetical purchase sorts after existing grants at the same instant; nothing is persisted.
     */
    public static Instant projectedExpiry(List<Grant> grants, Instant paidAt, int months) {
        var hypothetical = new ArrayList<>(grants);
        hypothetical.add(new Grant(Long.MAX_VALUE, paidAt, months));
        return replay(hypothetical).getLast().expiresAt();
    }

    private MembershipReplay() {}
}
