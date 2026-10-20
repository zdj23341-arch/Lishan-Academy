package com.wechat.jxsc.distribution;

import static com.wechat.jxsc.distribution.FinanceStore.*;

import com.wechat.jxsc.common.commerce.CommerceSupport;
import com.wechat.jxsc.common.content.*;

import org.springframework.boot.*;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Component
@Conditional(DevelopmentSeedCondition.class)
public class DevelopmentDistributionSeed implements ApplicationRunner {
    private final DevelopmentContentSeed content;
    private final DistributionAdministration admin;
    private final FinanceStore f;
    private final CommerceSupport s;
    private final TransactionTemplate tx;

    public DevelopmentDistributionSeed(
            DevelopmentContentSeed content,
            DistributionAdministration admin,
            FinanceStore f,
            CommerceSupport s,
            PlatformTransactionManager manager) {
        this.content = content;
        this.admin = admin;
        this.f = f;
        this.s = s;
        this.tx = new TransactionTemplate(manager);
    }

    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        content.initialize();
        for (String fixture : List.of("distributor-a", "distributor-b")) {
            long user =
                    f.db.queryForObject(
                            "select user_id from user_wechat_identity where app_id='DEV' and"
                                    + " openid=?",
                            Long.class,
                            fixture);
            var d =
                    admin.create(
                            new FinanceDtos.CreateDistributor(
                                    "" + user, "DEV distribution fixture"));
            for (long product :
                    f.db.queryForList(
                            "select id from product where seed_key like 'DEV-%' and"
                                    + " status='ON_SALE' order by id",
                            Long.class))
                tx.executeWithoutResult(
                        status -> {
                            f.one("select id from product where id=? for update", product);
                            f.one(
                                    "select id from distributor where id=? for update",
                                    Long.parseLong(d.id()));
                            if (f.db.queryForObject(
                                            "select count(*) from product_distributor_rule where"
                                                    + " product_id=? and distributor_id=?",
                                            Long.class,
                                            product,
                                            d.id())
                                    == 0) {
                                f.db.update(
                                        "insert into distribution_rule_set(product_id) values(?) on"
                                                + " duplicate key update product_id=product_id",
                                        product);
                                f.db.update(
                                        "insert into"
                                            + " product_distributor_rule(product_id,distributor_id,rate_bps,enabled,effective_at,rule_version,created_at,updated_at)"
                                            + " values(?,?,1250,true,?,0,?,?)",
                                        product,
                                        d.id(),
                                        f.now(),
                                        f.now(),
                                        f.now());
                                s.systemAudit(
                                        "DEV_DISTRIBUTION_RULE",
                                        "PRODUCT",
                                        product,
                                        "DEV fixture setup",
                                        Map.of("distributorId", d.id(), "rateBps", 1250));
                            }
                        });
        }
    }
}
