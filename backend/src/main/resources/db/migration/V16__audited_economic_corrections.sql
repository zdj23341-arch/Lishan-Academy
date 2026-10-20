ALTER TABLE withdrawal_reconciliation DROP CHECK withdrawal_reconciliation_chk_1,
    ADD CONSTRAINT chk_withdrawal_reconciliation_status CHECK(status IN ('PENDING_QUERY','NEEDS_REVIEW','RESOLVED'));
ALTER TABLE refund_transaction ADD dispatch_suppressed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE economic_resolution (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 resource_type VARCHAR(20) NOT NULL, transaction_id BIGINT UNSIGNED NOT NULL,
 order_id BIGINT UNSIGNED NULL, withdrawal_id BIGINT UNSIGNED NULL,
 channel_id VARCHAR(100) NOT NULL, observed_fen BIGINT NOT NULL,
 goods_fen BIGINT NOT NULL DEFAULT 0, freight_fen BIGINT NOT NULL DEFAULT 0,
 excess_fen BIGINT NOT NULL DEFAULT 0, evidence_json JSON NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL, reason VARCHAR(500) NOT NULL,
 request_id VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_economic_resolution(resource_type,transaction_id),
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(withdrawal_id) REFERENCES withdrawal_request(id),
 CHECK(resource_type IN ('REFUND','WITHDRAWAL')), CHECK(observed_fen>0),
 CHECK(goods_fen>=0 AND freight_fen>=0 AND excess_fen>=0),
 CHECK((resource_type='REFUND' AND order_id IS NOT NULL AND withdrawal_id IS NULL AND observed_fen=goods_fen+freight_fen+excess_fen)
    OR (resource_type='WITHDRAWAL' AND withdrawal_id IS NOT NULL AND order_id IS NULL))
);
CREATE TRIGGER economic_resolution_no_update BEFORE UPDATE ON economic_resolution FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Economic corrections are immutable';
CREATE TRIGGER economic_resolution_no_delete BEFORE DELETE ON economic_resolution FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Economic corrections are immutable';
