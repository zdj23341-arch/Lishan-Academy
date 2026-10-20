package com.wechat.jxsc.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.auth.TokenCodec;
import com.wechat.jxsc.catalog.*;
import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.common.content.ContentSupport;
import com.wechat.jxsc.common.storage.AssetService;
import com.wechat.jxsc.common.storage.FileAsset;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Supplier;

/** Database-only orchestration. Never call external upload/processing inside this transaction. */
@Service
public class CourseWorkflowService {
    @PersistenceContext private EntityManager em;
    private final CatalogConsistencyGuard guard;
    private final ProductService products;
    private final CourseService courses;
    private final AssetService assets;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public CourseWorkflowService(CatalogConsistencyGuard guard, ProductService products,
            CourseService courses, AssetService assets, JdbcTemplate db, ObjectMapper json) {
        this.guard = guard;
        this.products = products;
        this.courses = courses;
        this.assets = assets;
        this.db = db;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CourseWorkflowDtos.Workflow save(Long courseId, CourseWorkflowDtos.Write request) {
        guard.acquire(); // Global catalog order MUST precede receipt and entity locks.
        return json.convertValue(execute(request.operationKey(), courseId == null ? "CREATE" : "SAVE", courseId,
                request, () -> {
                    if (request.product().type() != Product.Type.COURSE)
                        throw ContentSupport.invalid("课程流程只能保存课程商品");
                    Course current = courseId == null ? null : lock(courseId);
                    if (current != null) {
                        ContentSupport.version(current.getVersion(), request.course().version());
                        var existing = ContentSupport.require(em, Product.class, current.productId);
                        if (existing.status.equals("ON_SALE"))
                            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                                    "请先下架课程，再保存修改并重新发布");
                    }
                    var product = products.save(current == null ? null : current.productId,
                            request.product());
                    var terms = request.course();
                    var course = courses.save(courseId, new CourseDtos.Write(terms.version(),
                            product.id(), terms.validityType(), terms.validityDays(),
                            terms.teacherName(), terms.durationMinutes()));
                    return workflow(Long.parseLong(course.id()));
                }), CourseWorkflowDtos.Workflow.class);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CourseWorkflowDtos.Workflow get(long courseId) {
        // One consistent snapshot includes product, course, and all chapter versions.
        return workflow(courseId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CourseWorkflowDtos.ChapterResult chapter(long courseId, CourseWorkflowDtos.ChapterCreate request) {
        guard.acquire();
        return json.convertValue(execute(request.operationKey(), "CHAPTER_CREATE", courseId, request, () -> {
            var course = lock(courseId);
            if (ContentSupport.require(em, Product.class, course.productId).status.equals("ON_SALE"))
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                        "请先下架课程，再添加章节并重新发布");
            var chapter = courses.chapter(null, courseId, request.chapter());
            return new CourseWorkflowDtos.ChapterResult(chapter, workflow(courseId));
        }), CourseWorkflowDtos.ChapterResult.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CourseWorkflowDtos.Workflow publish(long courseId, CourseWorkflowDtos.Publish request) {
        guard.acquire();
        return json.convertValue(execute(request.operationKey(), "PUBLISH", courseId, request, () -> {
            var course = lock(courseId);
            var product = ContentSupport.require(em, Product.class, course.productId);
            ContentSupport.version(product.getVersion(), request.productVersion());
            ContentSupport.version(course.getVersion(), request.courseVersion());
            if (product.priceFen < 0 || product.priceFen > 100000000
                    || (course.validityType.equals("DAYS")
                            && (course.validityDays == null || course.validityDays < 1))
                    || (course.validityType.equals("PERMANENT") && course.validityDays != null))
                throw ContentSupport.invalid("课程价格或观看有效期不正确");
            var chapters = chapters(courseId);
            var enabled = chapters.stream().filter(ch -> ch.enabled).toList();
            if (enabled.isEmpty()) throw ContentSupport.invalid("请至少启用一个已上传视频的章节");
            var assetIds = new TreeSet<Long>();
            if (product.coverAssetId == null) throw ContentSupport.invalid("请先设置可使用的课程封面");
            assetIds.add(product.coverAssetId);
            for (var block : product.contentBlocks)
                if (block.assetId() != null) assetIds.add(ContentSupport.id(block.assetId()));
            for (var chapter : enabled) {
                if (chapter.videoAssetId == null)
                    throw ContentSupport.invalid("启用章节必须绑定可使用的视频");
                assetIds.add(chapter.videoAssetId);
            }
            // Asset disable also takes the asset row lock. Refresh before READY validation.
            for (Long id : assetIds) guard.current(FileAsset.class, id);
            for (var chapter : enabled)
                assets.requirePurpose(chapter.videoAssetId, "COURSE_VIDEO", false);
            products.lifecycle(product.getId(),
                    new ContentSupport.Lifecycle(request.productVersion(), request.reason()), true);
            return workflow(courseId);
        }), CourseWorkflowDtos.Workflow.class);
    }

    @Transactional(readOnly = true)
    public CourseWorkflowDtos.Operation operation(String operationKey) {
        String key = key(operationKey);
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        var rows = db.queryForList("SELECT action,course_id,result_json FROM course_workflow_operation"
                + " WHERE actor_id=? AND operation_key=?", actor.id(), key);
        if (rows.isEmpty()) throw ContentSupport.missing();
        var row = rows.getFirst();
        return new CourseWorkflowDtos.Operation(key, row.get("action").toString(), "COMMITTED",
                row.get("course_id").toString(), parse(row.get("result_json").toString()));
    }

    private JsonNode execute(String operationKey, String action, Long courseId,
            Object request, Supplier<Object> work) {
        String key = key(operationKey);
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.ADMIN);
        var payload = json.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("operationKey");
        String hash = TokenCodec.hash(canonical(payload).toString());
        // Catalog guard already serializes initial insertion. A locking read avoids stale RR
        // snapshots even if this service is called from an existing transaction.
        var rows = db.queryForList("SELECT action,course_id,request_hash,result_json"
                + " FROM course_workflow_operation WHERE actor_id=? AND operation_key=? FOR UPDATE",
                actor.id(), key);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!action.equals(row.get("action")) || !hash.equals(row.get("request_hash"))
                    || (courseId != null
                            && courseId.longValue() != ((Number) row.get("course_id")).longValue()))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "操作编号已用于其他请求，请先查询原操作结果");
            return parse(row.get("result_json").toString());
        }
        Object value = work.get();
        JsonNode result = json.valueToTree(value);
        String id = value instanceof CourseWorkflowDtos.Workflow w ? w.course().id()
                : ((CourseWorkflowDtos.ChapterResult) value).workflow().course().id();
        em.flush();
        db.update("INSERT INTO course_workflow_operation"
                + " (actor_id,operation_key,action,course_id,request_hash,result_json) VALUES (?,?,?,?,?,?)",
                actor.id(), key, action, Long.parseLong(id), hash, result.toString());
        return result;
    }

    private Course lock(long courseId) {
        var ids = db.queryForList("SELECT product_id FROM course WHERE id=?",
                Long.class, courseId);
        if (ids.isEmpty()) throw ContentSupport.missing();
        var product = guard.current(Product.class, ids.getFirst());
        if (product.type != Product.Type.COURSE) throw ContentSupport.invalid("关联商品不是课程");
        return guard.current(Course.class, courseId);
    }

    private CourseWorkflowDtos.Workflow workflow(long courseId) {
        var course = ContentSupport.require(em, Course.class, courseId);
        var p = ContentSupport.require(em, Product.class, course.productId);
        if (p.type != Product.Type.COURSE) throw ContentSupport.invalid("关联商品不是课程");
        // Only COURSE: no getAdmin consistent child projections or REQUIRES_NEW boundaries.
        var product = new CatalogDtos.AdminProduct(p.getId().toString(), p.getVersion(), p.type,
                p.title, p.subtitle, p.coverAssetId == null ? null : p.coverAssetId.toString(),
                p.priceFen, p.originalPriceFen, p.status, p.sortNo,
                p.categoryIds.stream().map(Object::toString).toList(), p.contentBlocks, null, null);
        var chapters = chapters(courseId).stream().map(ch -> new CourseDtos.ChapterAdmin(
                ch.getId().toString(), ch.getVersion(), ch.courseId.toString(), ch.title, ch.sortNo,
                ch.videoAssetId == null ? null : ch.videoAssetId.toString(), ch.durationSeconds,
                ch.isTrial, ch.enabled)).toList();
        String stage = p.status.equals("ON_SALE") ? "PUBLISHED" : p.status;
        return new CourseWorkflowDtos.Workflow(stage, product, courses.get(courseId), chapters);
    }

    private List<CourseChapter> chapters(long courseId) {
        return em.createQuery("select c from CourseChapter c where c.courseId=:id order by c.sortNo,c.id",
                CourseChapter.class).setParameter("id", courseId).getResultList();
    }

    private String key(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw ContentSupport.invalid("操作编号必须是 UUID");
        return key.toLowerCase(Locale.ROOT);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid workflow receipt", e);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
