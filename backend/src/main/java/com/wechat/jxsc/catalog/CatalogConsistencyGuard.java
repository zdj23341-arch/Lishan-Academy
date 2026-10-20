package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.content.ContentSupport;

import jakarta.persistence.*;

import org.hibernate.Hibernate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes catalog invariants before any entity locks, including in an existing transaction. */
@Component
public class CatalogConsistencyGuard {
    @PersistenceContext private EntityManager em;
    private final JdbcTemplate db;

    public CatalogConsistencyGuard(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        db.queryForObject(
                "SELECT id FROM catalog_consistency_lock WHERE id=1 FOR UPDATE", Long.class);
    }

    /** Call after acquire; a caller may already hold an old snapshot or a managed entity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T current(Class<T> type, long id) {
        T value = em.find(type, id);
        if (value != null) {
            // Refresh directly: upgrading a stale managed version's lock can fail before reload.
            em.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        } else {
            // The row may have been created after an outer transaction's snapshot.
            value = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        }
        if (value == null) throw ContentSupport.missing();
        // Catalog entities use field access; never return a proxy with unpopulated public fields.
        return type.cast(Hibernate.unproxy(value));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean categoryIsReferenced(long id) {
        // Locking reads see current committed relations even after an earlier RR snapshot.
        return !db.queryForList(
                                "SELECT product_id FROM product_category WHERE category_id=? LIMIT"
                                        + " 1 FOR UPDATE",
                                Long.class,
                                id)
                        .isEmpty()
                || !db.queryForList(
                                "SELECT id FROM category WHERE parent_id=? LIMIT 1 FOR UPDATE",
                                Long.class,
                                id)
                        .isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MembershipPlan currentPlanForProduct(long id) {
        var ids =
                db.queryForList(
                        "SELECT id FROM membership_plan WHERE product_id=? FOR UPDATE",
                        Long.class,
                        id);
        if (ids.isEmpty()) throw ContentSupport.missing();
        return current(MembershipPlan.class, ids.getFirst());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enabledDurationExists(int months, long except) {
        return !db.queryForList(
                        "SELECT id FROM membership_plan WHERE duration_months=? AND"
                                + " status='ENABLED' AND id<>? LIMIT 1 FOR UPDATE",
                        Long.class,
                        months,
                        except)
                .isEmpty();
    }
}
