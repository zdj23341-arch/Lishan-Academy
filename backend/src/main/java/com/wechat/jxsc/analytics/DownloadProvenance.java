package com.wechat.jxsc.analytics;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.distribution.FinanceStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DownloadProvenance {
    private final FinanceStore f;
    private final DownloadSourceClassification source;

    public DownloadProvenance(FinanceStore f, DownloadSourceClassification source) {
        this.f = f;
        this.source = source;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long user, long asset, long paper, String action, String digest) {
        // Subject is already locked; current reads avoid the initial authorization RR snapshot.
        var interval =
                f.one(
                        "select grant_id from membership_interval where user_id=? and starts_at<=?"
                            + " and expires_at>? order by starts_at,grant_id limit 1 for update",
                        user,
                        f.now(),
                        f.now());
        var grant =
                f.one(
                        "select * from membership_grant where id=? for update",
                        n(interval, "grant_id"));
        String kind =
                grant.get("order_item_id") == null
                        ? "DEV_SEED"
                        : source.classify(n(grant, "order_item_id"));
        long id =
                f.insert(
                        "insert into"
                            + " download_audit(user_id,asset_id,paper_id,action,result,request_digest,created_at)"
                            + " values(?,?,?,?,'AUTHORIZED',?,?)",
                        user,
                        asset,
                        paper,
                        action,
                        digest,
                        f.now());
        f.db.update(
                "insert into"
                    + " download_provenance(audit_id,membership_grant_id,source_kind,created_at)"
                    + " values(?,?,?,?)",
                id,
                n(grant, "id"),
                kind,
                f.now());
    }
}
