-- Existing audits intentionally remain unclassified: mutable replay cannot prove historical source.
CREATE TABLE download_provenance (
 audit_id BIGINT UNSIGNED PRIMARY KEY, membership_grant_id BIGINT UNSIGNED NOT NULL,
 source_kind VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(audit_id) REFERENCES download_audit(id), FOREIGN KEY(membership_grant_id) REFERENCES membership_grant(id),
 CHECK(source_kind IN ('DEV_SEED','DEV_PAYMENT','NON_DEV_PAYMENT','NO_CHARGE','UNPROVEN'))
);
CREATE TRIGGER download_provenance_no_update BEFORE UPDATE ON download_provenance FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable download provenance';
CREATE TRIGGER download_provenance_no_delete BEFORE DELETE ON download_provenance FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable download provenance';
DELIMITER $$
CREATE TRIGGER commission_source_immutable BEFORE UPDATE ON commission_ledger FOR EACH ROW BEGIN
 IF NOT(OLD.order_id <=> NEW.order_id) OR NOT(OLD.order_item_id <=> NEW.order_item_id) OR NOT(OLD.distributor_id <=> NEW.distributor_id) OR NOT(OLD.base_fen <=> NEW.base_fen) OR NOT(OLD.rate_bps <=> NEW.rate_bps) OR NOT(OLD.earned_fen <=> NEW.earned_fen) OR NOT(OLD.observation_days <=> NEW.observation_days) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable commission source'; END IF;
END$$
DELIMITER ;
