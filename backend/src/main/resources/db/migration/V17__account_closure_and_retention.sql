CREATE TABLE account_lifecycle (
 user_id BIGINT UNSIGNED PRIMARY KEY, state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 FOREIGN KEY(user_id) REFERENCES user_account(id), CHECK(state IN ('ACTIVE','CLOSED'))
);
INSERT INTO account_lifecycle(user_id,state) SELECT id,IF(status='ACTIVE','ACTIVE','CLOSED') FROM user_account;
CREATE TRIGGER user_account_lifecycle AFTER INSERT ON user_account FOR EACH ROW INSERT INTO account_lifecycle(user_id,state) VALUES(NEW.id,IF(NEW.status='ACTIVE','ACTIVE','CLOSED'));
CREATE TABLE account_closure (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL UNIQUE,
 forfeited_access BOOLEAN NOT NULL, request_id VARCHAR(128) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES user_account(id)
);
CREATE TABLE retained_personal_data (
 user_id BIGINT UNSIGNED PRIMARY KEY, payload_cipher LONGTEXT NOT NULL,
 retain_until DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES user_account(id)
);
CREATE TRIGGER account_closure_no_update BEFORE UPDATE ON account_closure FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Account closure is append-only';
CREATE TRIGGER account_closure_no_delete BEFORE DELETE ON account_closure FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Account closure is append-only';
INSERT INTO admin_permission(code,created_at,updated_at) VALUES('FINANCIAL_RETENTION_READ',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO admin_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM admin_role r CROSS JOIN admin_permission p
 WHERE r.code IN ('SUPER_ADMIN','FINANCE_OPERATOR') AND p.code='FINANCIAL_RETENTION_READ';
