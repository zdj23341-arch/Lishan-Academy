package com.wechat.jxsc.common.content;

import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.common.PageQuery;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCandidateService {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminCandidateService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private record Source(String from, String id, String name, String status, String type,
                          String purpose, String productId, String courseId, String imageId,
                          String reason) {}

    private static Source source(String kind) {
        String image = " LEFT JOIN file_asset img ON img.id=x.cover_asset_id"
                + " AND img.status='READY' AND img.purpose='PUBLIC_IMAGE'";
        return switch (kind) {
            case "PRODUCT" -> new Source("product x LEFT JOIN course cr ON cr.product_id=x.id" + image,
                    "x.id", "x.title", "x.status", "x.type", "NULL", "x.id", "cr.id", "img.id",
                    "CASE WHEN x.status<>'ON_SALE' THEN 'PRODUCT_NOT_ON_SALE'"
                    + " WHEN x.type='COURSE' AND cr.id IS NULL THEN 'COURSE_NOT_LINKED' END");
            case "COURSE" -> new Source("course cr JOIN product x ON x.id=cr.product_id" + image,
                    "cr.id", "x.title", "x.status", "x.type", "NULL", "x.id", "cr.id", "img.id",
                    "CASE WHEN x.type<>'COURSE' THEN 'INVALID_COURSE_PRODUCT'"
                    + " WHEN x.status<>'ON_SALE' THEN 'COURSE_PRODUCT_NOT_ON_SALE' END");
            case "PAPER", "ARTICLE" -> new Source((kind.equals("PAPER") ? "paper" : "article") + " x" + image,
                    "x.id", "x.title", "x.status", kind.equals("PAPER") ? "x.paper_type" : "NULL",
                    "NULL", "NULL", "NULL", "img.id",
                    "CASE WHEN x.status<>'PUBLISHED' THEN '" + kind + "_NOT_PUBLISHED' END");
            case "ASSET" -> new Source("file_asset x", "x.id", "x.original_name", "x.status", "NULL",
                    "x.purpose", "NULL", "NULL",
                    "CASE WHEN x.status='READY' AND x.purpose='PUBLIC_IMAGE' THEN x.id END",
                    "CASE WHEN x.purpose='AFTERSALE_EVIDENCE' THEN 'ASSET_PURPOSE_NOT_SELECTABLE'"
                    + " WHEN x.status<>'READY' THEN 'ASSET_NOT_READY' END");
            default -> throw ContentSupport.invalid("Invalid candidate kind");
        };
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminCandidateDtos.Page find(String kind, String keyword, String type, String status,
            String purpose, List<String> ids, boolean usable, int page, int pageSize) {
        Source s = source(kind);
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        var pagination = new PageQuery(page, pageSize);
        keyword = AdminListFilters.keyword(keyword);
        type = type == null ? "" : type;
        status = status == null ? "" : status;
        purpose = purpose == null ? "" : purpose;
        validate(kind, type, status, purpose);
        if (ids.size() > 50) throw ContentSupport.invalid("At most 50 candidate IDs allowed");
        List<Long> selected = ids.stream().map(ContentSupport::id).distinct().sorted(Comparator.reverseOrder()).toList();
        if (!selected.isEmpty() && (!keyword.isEmpty() || !type.isEmpty() || !status.isEmpty() || !purpose.isEmpty()))
            throw ContentSupport.invalid("ID lookup cannot be combined with search filters");
        Long keywordId = keyword.matches("[0-9]+") ? ContentSupport.id(keyword) : null;
        var params = new MapSqlParameterSource().addValue("owner", actor.id())
                .addValue("keyword", keyword).addValue("keywordId", keywordId)
                .addValue("type", type).addValue("status", status)
                .addValue("purpose", purpose).addValue("limit", pageSize).addValue("offset", pagination.offset());
        String where = " WHERE 1=1";
        if (kind.equals("ASSET")) where += " AND ((x.owner_type='ADMIN' AND x.owner_id=:owner)"
                + " OR (x.owner_type='SYSTEM' AND x.seed_key IS NOT NULL))";
        if (!selected.isEmpty()) {
            params.addValue("ids", selected);
            where += " AND " + s.id + " IN (:ids)";
        } else {
            if (!keyword.isEmpty()) where += " AND (LOCATE(:keyword," + s.name + ")>0 OR " + s.id + "=:keywordId)";
            if (!type.isEmpty()) where += " AND " + s.type + "=:type";
            if (!status.isEmpty()) where += " AND " + s.status + "=:status";
            if (kind.equals("ASSET") && purpose.isEmpty()) purpose = "PUBLIC_IMAGE";
            params.addValue("purpose", purpose);
            if (!purpose.isEmpty()) where += " AND " + s.purpose + "=:purpose";
            if (usable) where += " AND (" + s.reason + ") IS NULL";
        }
        String select = "SELECT " + s.id + " id," + s.name + " name," + s.status + " status,"
                + s.purpose + " purpose," + s.productId + " product_id," + s.courseId + " course_id,"
                + s.imageId + " image_id," + s.reason + " reason FROM " + s.from + where;
        List<AdminCandidateDtos.Candidate> rows = jdbc.query(select + " ORDER BY " + s.id + " DESC"
                + (selected.isEmpty() ? " LIMIT :limit OFFSET :offset" : ""), params, (r, n) -> {
                    String imageId = r.getString("image_id");
                    String reason = r.getString("reason");
                    return new AdminCandidateDtos.Candidate(kind, r.getString("id"), r.getString("product_id"),
                            r.getString("course_id"), r.getString("name"),
                            imageId == null ? null : "/api/v1/assets/" + imageId + "/content",
                            r.getString("status"), r.getString("purpose"), reason == null, reason);
                });
        if (!selected.isEmpty()) {
            var byId = new HashMap<String, AdminCandidateDtos.Candidate>();
            rows.forEach(row -> byId.put(row.id(), row));
            var resolved = selected.stream().map(id -> byId.getOrDefault(id.toString(),
                    new AdminCandidateDtos.Candidate(kind, id.toString(), null, null, null, null,
                            null, null, false, "NOT_FOUND_OR_NOT_ACCESSIBLE"))).toList();
            int start = Math.min(pagination.offset(), resolved.size());
            return new AdminCandidateDtos.Page(resolved.subList(start, Math.min(start + pageSize, resolved.size())),
                    page, pageSize, resolved.size());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + s.from + where, params, Long.class);
        return new AdminCandidateDtos.Page(rows, page, pageSize, total);
    }

    private static void validate(String kind, String type, String status, String purpose) {
        Set<String> statuses = switch (kind) {
            case "PRODUCT", "COURSE" -> Set.of("DRAFT", "ON_SALE", "OFF_SALE");
            case "PAPER", "ARTICLE" -> Set.of("DRAFT", "PUBLISHED", "UNPUBLISHED");
            default -> Set.of("PENDING", "UPLOADED", "READY", "REJECTED", "DISABLED");
        };
        if (!status.isEmpty() && !statuses.contains(status)) throw ContentSupport.invalid("Invalid candidate status");
        if (!type.isEmpty() && !(kind.equals("PRODUCT") && Set.of("BOOK", "COURSE", "MEMBERSHIP").contains(type)
                || kind.equals("COURSE") && type.equals("COURSE")
                || kind.equals("PAPER") && type.length() <= 40)) throw ContentSupport.invalid("Invalid candidate type");
        if (!purpose.isEmpty() && !(kind.equals("ASSET")
                && Set.of("PUBLIC_IMAGE", "PAPER_ATTACHMENT", "COURSE_VIDEO").contains(purpose)))
            throw ContentSupport.invalid("Invalid asset purpose");
    }
}
