-- Serialize all observations for a merchant/day before taking a consistent read snapshot.
CREATE TABLE wechat_statement_day (
 merchant_id VARCHAR(64) NOT NULL, bill_date DATE NOT NULL,
 PRIMARY KEY(merchant_id,bill_date)
);
CREATE TABLE wechat_statement_availability (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 merchant_id VARCHAR(64) NOT NULL, statement_type VARCHAR(24) NOT NULL, bill_date DATE NOT NULL,
 availability VARCHAR(16) NOT NULL, evidence_hash CHAR(64) NOT NULL,
 evidence_cipher LONGTEXT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(merchant_id,statement_type,bill_date,availability,evidence_hash),
 CHECK(availability IN ('AVAILABLE','ABSENT','CREATING','UNKNOWN'))
);
CREATE TRIGGER wechat_statement_availability_no_update BEFORE UPDATE ON wechat_statement_availability FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Statement availability evidence is immutable';
CREATE TRIGGER wechat_statement_availability_no_delete BEFORE DELETE ON wechat_statement_availability FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Statement availability evidence is immutable';
