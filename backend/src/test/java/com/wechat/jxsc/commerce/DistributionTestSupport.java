package com.wechat.jxsc.commerce;

import java.util.*;

abstract class DistributionTestSupport extends EntitlementTestSupport {
    String ordinaryCustomer() {
        String name = "Ordinary-" + UUID.randomUUID();
        jdbc.update(
                "insert into user_account(nickname,status,created_at,updated_at,version)"
                        + " values(?,'ACTIVE',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name);
        long user =
                jdbc.queryForObject(
                        "select id from user_account where nickname=?", Long.class, name);
        jdbc.update(
                "insert into"
                    + " user_wechat_identity(user_id,app_id,openid,last_login_at,created_at,updated_at,version)"
                    + " values(?,'DEV',?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                user,
                name);
        byte[] entropy = new byte[32];
        new java.security.SecureRandom().nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.update(
                "insert into"
                    + " login_session(token_hash,actor_type,actor_id,user_id,expires_at,created_at,updated_at,version)"
                    + " values(?,'CUSTOMER',?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                com.wechat.jxsc.auth.TokenCodec.hash(token),
                user,
                user,
                java.sql.Timestamp.from(clock.now.plusSeconds(90 * 86400L)));
        return token;
    }

    String freshPromoter() throws Exception {
        String name = "Finance-" + UUID.randomUUID();
        jdbc.update(
                "insert into user_account(nickname,status,created_at,updated_at,version)"
                        + " values(?,'ACTIVE',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                name);
        long user =
                jdbc.queryForObject(
                        "select id from user_account where nickname=?", Long.class, name);
        jdbc.update(
                "insert into"
                    + " user_wechat_identity(user_id,app_id,openid,last_login_at,created_at,updated_at,version)"
                    + " values(?,'DEV',?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                user,
                name);
        byte[] entropy = new byte[32];
        new java.security.SecureRandom().nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.update(
                "insert into"
                    + " login_session(token_hash,actor_type,actor_id,user_id,expires_at,created_at,updated_at,version)"
                    + " values(?,'CUSTOMER',?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",
                com.wechat.jxsc.auth.TokenCodec.hash(token),
                user,
                user,
                java.sql.Timestamp.from(clock.now.plusSeconds(90 * 86400L)));
        call(
                "POST",
                "/admin/distributors",
                a,
                Map.of("userId", "" + user, "reason", "Isolated finance qualification"),
                200);
        return token;
    }

    String promoter(String fixture) throws Exception {
        String token = buyer(fixture);
        String id = call("GET", "/me", token, null, 200).path("id").asText();
        var profile =
                call(
                        "POST",
                        "/admin/distributors",
                        a,
                        Map.of("userId", id, "reason", "Test qualification"),
                        200);
        return token;
    }

    String did(String token) throws Exception {
        return call("GET", "/distribution/summary", token, null, 200)
                .path("distributorId")
                .asText();
    }

    String touch(String token, String product) throws Exception {
        String code =
                write("/distribution/referral-codes", token, Map.of("productId", product))
                        .path("code")
                        .asText();
        String t =
                call(
                                "POST",
                                "/referrals/resolve",
                                null,
                                Map.of("code", code, "visitorKey", UUID.randomUUID().toString()),
                                200)
                        .path("attributionToken")
                        .asText();
        call("POST", "/referrals/claim", b, Map.of("attributionToken", t), 200);
        return t;
    }

    void rule(String product, String... ids) throws Exception {
        List<Object> rules = new ArrayList<>();
        for (String id : ids)
            rules.add(Map.of("distributorId", id, "rateBps", 1250, "enabled", true));
        call(
                "PUT",
                "/admin/products/" + product + "/distributors",
                a,
                Map.of("version", 0, "rules", rules, "reason", "Test rules"),
                200);
    }
}
