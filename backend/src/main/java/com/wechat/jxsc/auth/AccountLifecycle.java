package com.wechat.jxsc.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/**
 * Admission mutex: always acquire before order/account/subject/user locks, never from callbacks.
 */
@Component
public class AccountLifecycle {
    private final JdbcTemplate db;

    public AccountLifecycle(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(long user) {
        var rows =
                db.queryForList(
                        "select state from account_lifecycle where user_id=? for update",
                        String.class,
                        user);
        if (rows.size() != 1 || !rows.getFirst().equals("ACTIVE"))
            throw CurrentActor.unauthorized();
        var users = db.queryForList("select status from user_account where id=? for share", String.class, user);
        if (users.size() != 1 || !users.getFirst().equals("ACTIVE"))
            throw CurrentActor.unauthorized();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void customer() {
        var actor = CurrentActor.require();
        actor.requireType(CurrentActor.Type.CUSTOMER);
        requireActive(actor.id());
    }
}
