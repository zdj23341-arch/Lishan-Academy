package com.wechat.jxsc.common.content;

import java.util.List;

public final class AdminCandidateDtos {
    private AdminCandidateDtos() {}

    public record Page(List<Candidate> items, int page, int pageSize, long total) {}

    public record Candidate(
            String kind,
            String id,
            String productId,
            String courseId,
            String name,
            String thumbnail,
            String status,
            String purpose,
            boolean usable,
            String availabilityReason) {}
}
