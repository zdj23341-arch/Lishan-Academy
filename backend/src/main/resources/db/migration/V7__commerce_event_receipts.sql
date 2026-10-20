CREATE TABLE commerce_event_receipt (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_type VARCHAR(40) NOT NULL, business_key VARCHAR(128) NOT NULL,
 consumer_name VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_event_consumer(event_type,business_key,consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TRIGGER commerce_receipt_no_update BEFORE UPDATE ON commerce_event_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable commerce receipt';
CREATE TRIGGER commerce_receipt_no_delete BEFORE DELETE ON commerce_event_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable commerce receipt';

CREATE TRIGGER callback_payload_immutable BEFORE UPDATE ON callback_inbox FOR EACH ROW
BEGIN
 IF NOT (OLD.provider <=> NEW.provider) OR NOT (OLD.event_id <=> NEW.event_id)
 OR NOT (OLD.resource_type <=> NEW.resource_type) OR NOT (OLD.signature_valid <=> NEW.signature_valid)
 OR NOT (OLD.payload_encrypted <=> NEW.payload_encrypted) OR NOT (OLD.payload_hash <=> NEW.payload_hash)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable callback evidence'; END IF;
END;
