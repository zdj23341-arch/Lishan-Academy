CREATE TABLE operation_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_type VARCHAR(16) NOT NULL, actor_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(64) NOT NULL, resource_type VARCHAR(64) NOT NULL, resource_id VARCHAR(64) NOT NULL,
    before_json JSON NULL, after_json JSON NULL, reason VARCHAR(500) NOT NULL, request_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    INDEX ix_audit_resource(resource_type,resource_id,created_at), INDEX ix_audit_actor(actor_type,actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TRIGGER audit_no_update BEFORE UPDATE ON operation_audit_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit records are append-only';
CREATE TRIGGER audit_no_delete BEFORE DELETE ON operation_audit_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit records are append-only';

CREATE TABLE idempotency_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_type VARCHAR(16) NOT NULL, actor_id BIGINT UNSIGNED NOT NULL,
    scope VARCHAR(64) COLLATE ascii_bin NOT NULL, operation_key VARCHAR(36) COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) COLLATE ascii_bin NOT NULL, state VARCHAR(16) NOT NULL,
    response_json JSON NULL, resource_id VARCHAR(64) NULL,
    expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_idempotency_actor_scope_key(actor_type,actor_id,scope,operation_key), INDEX ix_idempotency_expiry(expires_at),
    CHECK (state IN ('PROCESSING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
