ALTER TABLE payment_transaction ADD dispatch_started_at DATETIME(6) NULL,
 ADD payer_cipher TEXT NULL, ADD description VARCHAR(127) NULL, ADD product_path VARCHAR(40) NULL,
 ADD params_cipher TEXT NULL;
-- Existing non-DEV intents have no reliable historical dispatch marker. Recovery is query-only.
UPDATE payment_transaction SET dispatch_started_at=created_at WHERE channel<>'DEV';
ALTER TABLE distributor ADD recipient_name_cipher TEXT NULL;
ALTER TABLE withdrawal_request ADD recipient_name_cipher TEXT NULL;
ALTER TABLE withdrawal_request ADD cancel_requested BOOLEAN NOT NULL DEFAULT FALSE, ADD cancel_dispatch_started_at DATETIME(6) NULL;
CREATE TABLE wechat_identity_guard (identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY);
CREATE TABLE wechat_evidence (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 channel VARCHAR(32) NOT NULL, resource_type VARCHAR(24) NOT NULL, business_no VARCHAR(64) COLLATE ascii_bin NOT NULL,
 event_key VARCHAR(128) COLLATE ascii_bin NOT NULL, payload_hash CHAR(64) NOT NULL, payload_cipher LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(channel,event_key)
);
CREATE TRIGGER wechat_evidence_no_update BEFORE UPDATE ON wechat_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Authenticated channel evidence is immutable';
CREATE TRIGGER wechat_evidence_no_delete BEFORE DELETE ON wechat_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Authenticated channel evidence is immutable';
CREATE TABLE channel_statement (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, channel VARCHAR(32) NOT NULL, merchant_id VARCHAR(64) NOT NULL,
 statement_type VARCHAR(24) NOT NULL, bill_date DATE NOT NULL, hash_value CHAR(40) NOT NULL,
 source_cipher LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(channel,merchant_id,statement_type,bill_date)
);
CREATE TRIGGER channel_statement_no_update BEFORE UPDATE ON channel_statement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Channel statements are immutable';
CREATE TRIGGER channel_statement_no_delete BEFORE DELETE ON channel_statement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Channel statements are immutable';
CREATE TABLE reconciliation_issue (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, channel VARCHAR(32) NOT NULL, business_no VARCHAR(64) NOT NULL,
 resource_type VARCHAR(24) NOT NULL, issue_code VARCHAR(64) NOT NULL, bill_date DATE NOT NULL,
 local_amount_fen BIGINT NULL, channel_amount_fen BIGINT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW', created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(channel,resource_type,business_no,issue_code,bill_date)
);
