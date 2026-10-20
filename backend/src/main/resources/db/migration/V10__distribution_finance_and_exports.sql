-- Immutable anchors are established while provisioning identities, never in order-first consumers.
CREATE TABLE distribution_identity (
 identity_id BIGINT UNSIGNED PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(identity_id) REFERENCES user_wechat_identity(id), FOREIGN KEY(user_id) REFERENCES user_account(id)
);
CREATE TABLE distributor (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL UNIQUE,
 recipient_identity_id BIGINT UNSIGNED NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 risk_blocked BOOLEAN NOT NULL DEFAULT FALSE, reason VARCHAR(500) NOT NULL,
 receipt_ready BOOLEAN NOT NULL DEFAULT FALSE, approved_at DATETIME(6) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES user_account(id), FOREIGN KEY(recipient_identity_id) REFERENCES distribution_identity(identity_id),
 CHECK(status IN ('ACTIVE','DISABLED'))
);
CREATE TABLE distribution_rule_set (
 product_id BIGINT UNSIGNED PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(product_id) REFERENCES product(id)
);
CREATE TABLE product_distributor_rule (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, product_id BIGINT UNSIGNED NOT NULL,
 distributor_id BIGINT UNSIGNED NOT NULL, rate_bps INT NOT NULL, enabled BOOLEAN NOT NULL,
 effective_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NULL, rule_version BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE(product_id,distributor_id), FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(distributor_id) REFERENCES distributor(id),
 CHECK(rate_bps BETWEEN 0 AND 10000), CHECK(expires_at IS NULL OR expires_at>effective_at)
);
CREATE TABLE referral_code (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, code VARCHAR(48) COLLATE ascii_bin NOT NULL UNIQUE,
 product_id BIGINT UNSIGNED NOT NULL, distributor_id BIGINT UNSIGNED NOT NULL, enabled BOOLEAN NOT NULL,
 expires_at DATETIME(6) NOT NULL, share_version INT NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(distributor_id) REFERENCES distributor(id)
);
CREATE TABLE referral_touch (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, code_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT UNSIGNED NULL, visitor_key_hash CHAR(64) NULL, token_hash CHAR(64) COLLATE ascii_bin NOT NULL UNIQUE,
 touched_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY(code_id) REFERENCES referral_code(id), FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(user_id) REFERENCES user_account(id),
 KEY ix_referral_latest(user_id,product_id,touched_at,id)
);
CREATE TABLE attribution (
 order_id BIGINT UNSIGNED PRIMARY KEY, distributor_id BIGINT UNSIGNED NOT NULL, referral_touch_id BIGINT UNSIGNED NOT NULL,
 rate_bps INT NOT NULL, rule_version BIGINT NOT NULL, base_fen BIGINT NOT NULL, observation_days INT NOT NULL,
 bound_at DATETIME(6) NOT NULL, strategy_version INT NOT NULL DEFAULT 1,
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(distributor_id) REFERENCES distributor(id),
 FOREIGN KEY(referral_touch_id) REFERENCES referral_touch(id), CHECK(rate_bps BETWEEN 0 AND 10000), CHECK(base_fen>=0)
);
CREATE TABLE commission_account (
 distributor_id BIGINT UNSIGNED PRIMARY KEY, pending_fen BIGINT NOT NULL DEFAULT 0, available_fen BIGINT NOT NULL DEFAULT 0,
 withdrawing_fen BIGINT NOT NULL DEFAULT 0, paid_out_fen BIGINT NOT NULL DEFAULT 0, debt_fen BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(distributor_id) REFERENCES distributor(id),
 CHECK(pending_fen>=0 AND available_fen>=0 AND withdrawing_fen>=0 AND paid_out_fen>=0 AND debt_fen>=0)
);
CREATE TABLE commission_ledger (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, order_id BIGINT UNSIGNED NOT NULL UNIQUE,
 order_item_id BIGINT UNSIGNED NOT NULL UNIQUE, distributor_id BIGINT UNSIGNED NOT NULL,
 base_fen BIGINT NOT NULL, rate_bps INT NOT NULL, earned_fen BIGINT NOT NULL, reversed_fen BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL, eligible_at DATETIME(6) NULL, observation_days INT NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(order_item_id) REFERENCES order_item(id),
 FOREIGN KEY(distributor_id) REFERENCES commission_account(distributor_id), KEY ix_commission_due(state,eligible_at),
 CHECK(base_fen>=0 AND earned_fen>=reversed_fen AND reversed_fen>=0), CHECK(rate_bps BETWEEN 0 AND 10000),
 CHECK(state IN ('PENDING','AVAILABLE'))
);
CREATE TABLE withdrawal_request (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, withdrawal_no VARCHAR(32) COLLATE ascii_bin NOT NULL UNIQUE,
 distributor_id BIGINT UNSIGNED NOT NULL, amount_fen BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 out_bill_no VARCHAR(32) COLLATE ascii_bin NOT NULL UNIQUE, channel_bill_no VARCHAR(128) NULL,
 recipient_identity_id BIGINT UNSIGNED NOT NULL, recipient_cipher TEXT NOT NULL,
 app_id VARCHAR(128) NOT NULL, merchant_id VARCHAR(128) NOT NULL, channel VARCHAR(24) NOT NULL,
 provider_state VARCHAR(64) NULL, error_code VARCHAR(64) NULL, confirmation_cipher TEXT NULL,
 dispatch_started_at DATETIME(6) NULL, next_query_at DATETIME(6) NULL, last_refresh_at DATETIME(6) NULL,
 query_count INT NOT NULL DEFAULT 0, alerted BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(distributor_id) REFERENCES commission_account(distributor_id), FOREIGN KEY(recipient_identity_id) REFERENCES distribution_identity(identity_id),
 CHECK(amount_fen>0), CHECK(status IN ('REQUESTED','PROCESSING','UNKNOWN','USER_ACTION_REQUIRED','SUCCEEDED','FAILED','CLOSED')),
 KEY ix_withdrawal_due(status,next_query_at)
);
CREATE TABLE commission_account_ledger (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, distributor_id BIGINT UNSIGNED NOT NULL,
 business_key VARCHAR(180) COLLATE ascii_bin NOT NULL UNIQUE, event_type VARCHAR(32) NOT NULL,
 pending_delta BIGINT NOT NULL, available_delta BIGINT NOT NULL, withdrawing_delta BIGINT NOT NULL,
 paid_out_delta BIGINT NOT NULL, debt_delta BIGINT NOT NULL, before_json JSON NOT NULL, after_json JSON NOT NULL,
 order_id BIGINT UNSIGNED NULL, refund_id BIGINT UNSIGNED NULL, withdrawal_id BIGINT UNSIGNED NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(distributor_id) REFERENCES commission_account(distributor_id), FOREIGN KEY(order_id) REFERENCES store_order(id),
 FOREIGN KEY(refund_id) REFERENCES refund_transaction(id), FOREIGN KEY(withdrawal_id) REFERENCES withdrawal_request(id),
 KEY ix_account_flow(distributor_id,created_at,id)
);
CREATE TABLE withdrawal_event (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, withdrawal_id BIGINT UNSIGNED NOT NULL,
 event_key VARCHAR(180) COLLATE ascii_bin NOT NULL UNIQUE, source VARCHAR(24) NOT NULL,
 provider_state VARCHAR(64) NOT NULL, result VARCHAR(32) NOT NULL, payload_hash CHAR(64) NOT NULL,
 detail_cipher TEXT NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(withdrawal_id) REFERENCES withdrawal_request(id)
);
CREATE TABLE transfer_simulation (
 withdrawal_id BIGINT UNSIGNED PRIMARY KEY, scenario VARCHAR(32) NOT NULL, provider_state VARCHAR(32) NOT NULL,
 send_count INT NOT NULL DEFAULT 0, query_count INT NOT NULL DEFAULT 0,
 FOREIGN KEY(withdrawal_id) REFERENCES withdrawal_request(id)
);
CREATE TABLE finance_settings (
 id INT PRIMARY KEY, min_fen BIGINT NOT NULL, max_fen BIGINT NOT NULL, daily_max_count INT NOT NULL,
 observation_days INT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CHECK(min_fen>=100 AND max_fen>=min_fen AND max_fen<=100000 AND daily_max_count BETWEEN 1 AND 5), CHECK(observation_days BETWEEN 7 AND 90)
);
INSERT INTO finance_settings VALUES(1,100,100000,5,7,0);
CREATE TABLE admin_export (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, requester_id BIGINT UNSIGNED NOT NULL, export_type VARCHAR(24) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(16) NOT NULL, content_cipher LONGTEXT NULL,
 row_count INT NOT NULL DEFAULT 0, expires_at DATETIME(6) NULL, error_code VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(requester_id) REFERENCES admin_user(id), CHECK(status IN ('REQUESTED','SUCCEEDED','FAILED'))
);
INSERT INTO admin_permission(code,created_at,updated_at) VALUES('EXPORT_READ',UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO admin_role_permission SELECT r.id,p.id FROM admin_role r,admin_permission p WHERE p.code='EXPORT_READ' AND r.code IN ('SUPER_ADMIN','ANALYST','FINANCE_OPERATOR','ORDER_OPERATOR','DISTRIBUTION_OPERATOR');
DELIMITER $$
CREATE TRIGGER distribution_identity_no_update BEFORE UPDATE ON distribution_identity FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable distribution identity'; END$$
CREATE TRIGGER distribution_identity_no_delete BEFORE DELETE ON distribution_identity FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable distribution identity'; END$$
CREATE TRIGGER attribution_no_update BEFORE UPDATE ON attribution FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable attribution'; END$$
CREATE TRIGGER attribution_no_delete BEFORE DELETE ON attribution FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable attribution'; END$$
CREATE TRIGGER commission_account_ledger_no_update BEFORE UPDATE ON commission_account_ledger FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable financial ledger'; END$$
CREATE TRIGGER commission_account_ledger_no_delete BEFORE DELETE ON commission_account_ledger FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable financial ledger'; END$$
CREATE TRIGGER withdrawal_event_no_update BEFORE UPDATE ON withdrawal_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable withdrawal event'; END$$
CREATE TRIGGER withdrawal_event_no_delete BEFORE DELETE ON withdrawal_event FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable withdrawal event'; END$$
CREATE TRIGGER withdrawal_identity_immutable BEFORE UPDATE ON withdrawal_request FOR EACH ROW BEGIN
 IF NOT(OLD.distributor_id <=> NEW.distributor_id) OR NOT(OLD.amount_fen <=> NEW.amount_fen) OR NOT(OLD.out_bill_no <=> NEW.out_bill_no) OR NOT(OLD.recipient_identity_id <=> NEW.recipient_identity_id) OR NOT(OLD.recipient_cipher <=> NEW.recipient_cipher) OR NOT(OLD.app_id <=> NEW.app_id) OR NOT(OLD.merchant_id <=> NEW.merchant_id) OR NOT(OLD.channel <=> NEW.channel) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable withdrawal intent'; END IF;
END$$
DELIMITER ;
