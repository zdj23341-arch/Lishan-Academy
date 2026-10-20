package com.wechat.jxsc.course;

import com.wechat.jxsc.membership.EntitlementAccess;

/** Learning grouping is separate from the entitlement status returned in CourseView. */
public enum LearningFilter {
    IN_PROGRESS,
    COMPLETED,
    EXPIRED;

    public boolean includes(EntitlementAccess.Right right, int completed, int total) {
        return switch (this) {
            case IN_PROGRESS -> right.active() && (total == 0 || completed < total);
            case COMPLETED -> right.active() && total > 0 && completed == total;
            case EXPIRED -> right.status().equals("EXPIRED");
        };
    }
}
