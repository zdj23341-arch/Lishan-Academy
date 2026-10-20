-- Existing shipments were created for the original purchase quantity. Preserve that history;
-- subtracting current restored_quantity would confuse later warehouse returns with pre-shipment refunds.
ALTER TABLE shipment ADD shipped_quantity INT NULL;
UPDATE shipment s JOIN order_item i ON i.order_id=s.order_id SET s.shipped_quantity=i.quantity;
ALTER TABLE shipment MODIFY shipped_quantity INT NOT NULL, ADD CHECK(shipped_quantity BETWEEN 1 AND 99);
DELIMITER $$
CREATE TRIGGER shipment_quantity_immutable BEFORE UPDATE ON shipment FOR EACH ROW
BEGIN
 IF NEW.shipped_quantity<>OLD.shipped_quantity THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Shipment quantity is immutable';
 END IF;
END$$
DELIMITER ;

ALTER TABLE store_order ADD financial_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE refund_transaction ADD dispatch_started_at DATETIME(6) NULL;
-- Before V8 a process could crash during provider I/O while the row still said CREATED.
-- Conservatively treat every pre-upgrade intent as possibly dispatched and recover by query.
UPDATE refund_transaction SET dispatch_started_at=created_at;
CREATE TABLE financial_reconciliation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL, resource_type VARCHAR(20) NOT NULL, transaction_id BIGINT UNSIGNED NOT NULL,
 first_inbox_id BIGINT UNSIGNED NULL, amount_fen BIGINT NOT NULL, reason VARCHAR(100) NOT NULL,
 status VARCHAR(24) NOT NULL, last_query_state VARCHAR(24) NOT NULL, channel_id VARCHAR(100) NULL,
 success_observed BOOLEAN NOT NULL,
 UNIQUE KEY uk_reconciliation_resource(resource_type,transaction_id), INDEX ix_reconciliation_order(order_id,status),
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(first_inbox_id) REFERENCES callback_inbox(id),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE reconciliation_query (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 reconciliation_id BIGINT UNSIGNED NOT NULL, source VARCHAR(24) NOT NULL, payload_encrypted TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_reconciliation_query(reconciliation_id,payload_hash),
 FOREIGN KEY(reconciliation_id) REFERENCES financial_reconciliation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELIMITER $$
CREATE TRIGGER reconciliation_query_no_update BEFORE UPDATE ON reconciliation_query FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Reconciliation evidence is immutable'; END$$
CREATE TRIGGER reconciliation_query_no_delete BEFORE DELETE ON reconciliation_query FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Reconciliation evidence is immutable'; END$$
DELIMITER ;
