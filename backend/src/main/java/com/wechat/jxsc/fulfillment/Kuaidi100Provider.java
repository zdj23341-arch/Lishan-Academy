package com.wechat.jxsc.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.common.ApiException;

import org.springframework.core.env.*;
import org.springframework.http.HttpStatus;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** The exact serialized param is signed and sent unchanged. No recipient data is logged. */
public final class Kuaidi100Provider implements LogisticsProvider {
    private static final ScheduledExecutorService BODY_DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "logistics-body-deadlines");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final String customer, key;
    private final URI endpoint;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final ObjectMapper json = new ObjectMapper();

    Kuaidi100Provider(Environment env) {
        customer = env.getProperty("jxsc.logistics.customer", "");
        key = env.getProperty("jxsc.logistics.key", "");
        endpoint =
                URI.create(
                        env.getProperty(
                                "jxsc.logistics.endpoint",
                                "https://poll.kuaidi100.com/poll/query.do"));
        boolean test =
                env.acceptsProfiles(Profiles.of("test"))
                        && !env.acceptsProfiles(Profiles.of("prod"));
        if (!endpoint.toString().equals("https://poll.kuaidi100.com/poll/query.do")
                && !(test
                        && endpoint.getScheme().equals("http")
                        && endpoint.getHost().equals("127.0.0.1")))
            throw new IllegalStateException(
                    "jxsc.logistics.endpoint must be the official Kuaidi100 endpoint");
    }

    public Tracking query(String carrier, String tracking) {
        return query(carrier, tracking, null);
    }

    @Override
    public Tracking query(String carrier, String tracking, String phone) {
        try {
            if (customer.isBlank()
                    || key.isBlank()
                    || !carrier.matches("[a-z0-9]{2,40}")
                    || !tracking.matches("[A-Za-z0-9-]{6,32}")) throw unavailable();
            var param = new LinkedHashMap<String, Object>();
            param.put("com", carrier);
            param.put("num", tracking);
            param.put("resultv2", "4");
            if (Set.of("shunfeng", "shunfengkuaiyun", "zhongtong").contains(carrier)) {
                if (phone == null || !phone.matches("[0-9+ -]{4,20}")) throw unavailable();
                param.put("phone", phone);
            }
            String raw = json.writeValueAsString(param);
            String sign =
                    HexFormat.of()
                            .withUpperCase()
                            .formatHex(
                                    MessageDigest.getInstance("MD5")
                                            .digest(
                                                    (raw + key + customer)
                                                            .getBytes(StandardCharsets.UTF_8)));
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "customer="
                                                    + enc(customer)
                                                    + "&param="
                                                    + enc(raw)
                                                    + "&sign="
                                                    + sign))
                            .build();
            var reply = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            var expired = new AtomicBoolean();
            // Request timeout covers obtaining the response; streaming consumption needs its own
            // fixed deadline, including a peer that never sends another byte after headers.
            var deadline =
                    BODY_DEADLINES.schedule(
                            () -> {
                                expired.set(true);
                                try {
                                    reply.body().close();
                                } catch (java.io.IOException ignored) {
                                }
                            },
                            15,
                            TimeUnit.SECONDS);
            try (var body = reply.body()) {
                bytes = body.readNBytes(1048577);
            } finally {
                deadline.cancel(false);
            }
            if (expired.get() || reply.statusCode() != 200 || bytes.length > 1048576)
                throw unavailable();
            var root = json.readTree(bytes);
            if (!root.path("status").asText().equals("200")
                    || !root.path("com").asText().equals(carrier)
                    || !root.path("nu").asText().equals(tracking)
                    || !root.path("data").isArray()
                    || root.path("data").size() > 1000) throw unavailable();
            var events = new LinkedHashSet<FulfillmentDtos.Event>();
            for (var event : root.path("data")) {
                String message = event.path("context").asText();
                if (message.length() > 2000) throw unavailable();
                var at =
                        LocalDateTime.parse(
                                        event.path("ftime").asText(),
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                .atZone(ZoneId.of("Asia/Shanghai"))
                                .toInstant();
                events.add(new FulfillmentDtos.Event(at, message));
            }
            String state =
                    switch (root.path("state").asText()) {
                        case "3" -> "SIGNED";
                        case "4" -> "RETURNED";
                        case "14" -> "REFUSED";
                        case "0", "1", "5", "6", "8", "10", "11", "12", "13" -> "IN_TRANSIT";
                        case "2" -> "EXCEPTION";
                        default -> "UNKNOWN";
                    };
            return new Tracking(state, false, List.copyOf(events));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EXTERNAL_SERVICE_UNAVAILABLE",
                "Logistics query unavailable; cached tracks retained");
    }
}
