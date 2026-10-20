package com.wechat.jxsc.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.auth.CurrentActor;
import com.wechat.jxsc.auth.TokenCodec;

import jakarta.persistence.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    public enum State {
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    public record Outcome(State state, String resourceId, JsonNode response) {
        public Outcome {
            Objects.requireNonNull(state);
            Objects.requireNonNull(response);
        }
    }

    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(ObjectMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    /**
     * Joins the caller's transaction. The callback may only perform local DB work: persist an
     * outbox intent here, then call external providers after commit.
     */
    @Transactional
    public Outcome execute(
            CurrentActor actor,
            String scope,
            String key,
            JsonNode request,
            Supplier<Outcome> databaseWork) {
        key = validate(scope, key);
        String hash = TokenCodec.hash(canonical(Objects.requireNonNull(request)).toString());
        var now = clock.instant();
        // MySQL's unique index serializes same-key calls, including the initial insert.
        // Both reservation and response participate in the business transaction.
        em.createNativeQuery(
                        """
INSERT INTO idempotency_record(actor_type,actor_id,scope,operation_key,request_hash,state,expires_at,created_at,updated_at)
VALUES (:type,:actor,:scope,:key,:hash,'PROCESSING',:expires,:now,:now)
ON DUPLICATE KEY UPDATE id=id
""")
                .setParameter("type", actor.type().name())
                .setParameter("actor", actor.id())
                .setParameter("scope", scope)
                .setParameter("key", key)
                .setParameter("hash", hash)
                .setParameter("expires", Timestamp.from(now.plus(Duration.ofDays(7))))
                .setParameter("now", Timestamp.from(now))
                .executeUpdate();
        var record =
                query(actor, scope, key)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getSingleResult();
        if (!record.requestHash.equals(hash))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency key was used with different request fields");
        if (record.responseJson != null) return outcome(record);
        var result = Objects.requireNonNull(databaseWork.get());
        record.state = result.state();
        record.resourceId = result.resourceId();
        record.responseJson = result.response().deepCopy();
        record.updatedAt = clock.instant();
        em.flush();
        return outcome(record);
    }

    @Transactional(readOnly = true)
    public Outcome lookup(CurrentActor actor, String scope, String key) {
        key = validate(scope, key);
        var records = query(actor, scope, key).getResultList();
        if (records.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Operation not found");
        return outcome(records.getFirst());
    }

    private TypedQuery<IdempotencyRecord> query(CurrentActor actor, String scope, String key) {
        return em.createQuery(
                        "select i from IdempotencyRecord i where i.actorType=:type and"
                                + " i.actorId=:actor and i.scope=:scope and i.operationKey=:key",
                        IdempotencyRecord.class)
                .setParameter("type", actor.type().name())
                .setParameter("actor", actor.id())
                .setParameter("scope", scope)
                .setParameter("key", key);
    }

    private Outcome outcome(IdempotencyRecord record) {
        return new Outcome(record.state, record.resourceId, record.responseJson.deepCopy());
    }

    private String validate(String scope, String key) {
        if (scope == null
                || !scope.matches("[A-Z][A-Z0-9_]{0,63}")
                || key == null
                || !key.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "A UUID idempotency key and action scope are required");
        return key.toLowerCase(Locale.ROOT);
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = json.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }
}
