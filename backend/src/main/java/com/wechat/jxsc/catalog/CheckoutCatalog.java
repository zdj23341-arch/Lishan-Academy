package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.commerce.*;
import com.wechat.jxsc.course.Course;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Current locking snapshot; independent child versions remain part of checkout's contract. */
@Service
public class CheckoutCatalog {
    @PersistenceContext private EntityManager em;
    private final CommerceSupport support;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wechat.jxsc.common.storage.AssetService assets;

    public CheckoutCatalog(CommerceSupport support) {
        this.support = support;
    }

    public record Snapshot(
            long productId,
            long version,
            Long termsVersion,
            String type,
            String title,
            long unitPriceFen,
            int quantityLimit,
            Map<String, Object> fulfillment) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot read(long id) {
        var p = support.lock(Product.class, id);
        if (!p.status.equals("ON_SALE")) throw CommerceSupport.stale();
        Map<String, Object> f = new LinkedHashMap<>();
        Long terms = null;
        if (p.type == Product.Type.COURSE) {
            var c =
                    em.createQuery("select c from Course c where c.productId=:p", Course.class)
                            .setParameter("p", id)
                            .setLockMode(LockModeType.PESSIMISTIC_READ)
                            .getSingleResult();
            em.refresh(c, LockModeType.PESSIMISTIC_READ);
            terms = c.getVersion();
            f.put("courseId", c.getId().toString());
            f.put("validityType", c.validityType);
            f.put("validityDays", c.validityDays);
        }
        if (p.type == Product.Type.MEMBERSHIP) {
            var m =
                    em.createQuery(
                                    "select m from MembershipPlan m where m.productId=:p",
                                    MembershipPlan.class)
                            .setParameter("p", id)
                            .setLockMode(LockModeType.PESSIMISTIC_READ)
                            .getSingleResult();
            em.refresh(m, LockModeType.PESSIMISTIC_READ);
            if (!m.status.equals("ENABLED")) throw CommerceSupport.stale();
            terms = m.getVersion();
            f.put("planId", m.getId().toString());
            f.put("durationMonths", m.durationMonths);
        }
        f.put("coverUrl", assets.publicUrl(p.coverAssetId));
        f.put("productVersion", p.getVersion());
        f.put("termsVersion", terms);
        return new Snapshot(
                id,
                p.getVersion(),
                terms,
                p.type.name(),
                p.title,
                p.priceFen,
                p.type == Product.Type.BOOK ? p.quantityLimit : 1,
                f);
    }
}
