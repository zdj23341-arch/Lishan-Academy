ALTER TABLE product ADD COLUMN first_published_at DATETIME(6) NULL;
ALTER TABLE paper ADD COLUMN first_published_at DATETIME(6) NULL;
-- Legacy rows use their creation time as a deterministic fallback; not migration time.
UPDATE product SET first_published_at=created_at WHERE status IN ('ON_SALE','OFF_SALE');
UPDATE paper SET first_published_at=created_at WHERE status IN ('PUBLISHED','UNPUBLISHED');
CREATE TRIGGER product_first_publish BEFORE UPDATE ON product FOR EACH ROW
SET NEW.first_published_at = CASE
    WHEN OLD.first_published_at IS NOT NULL THEN OLD.first_published_at
    WHEN NEW.status='ON_SALE' THEN UTC_TIMESTAMP(6) ELSE NULL END;
CREATE TRIGGER paper_first_publish BEFORE UPDATE ON paper FOR EACH ROW
SET NEW.first_published_at = CASE
    WHEN OLD.first_published_at IS NOT NULL THEN OLD.first_published_at
    WHEN NEW.status='PUBLISHED' THEN UTC_TIMESTAMP(6) ELSE NULL END;
CREATE TABLE home_selection_snapshot (
    revision BIGINT NOT NULL PRIMARY KEY,
    rules_json JSON NOT NULL,
    FOREIGN KEY (revision) REFERENCES home_layout(revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TRIGGER home_selection_no_update BEFORE UPDATE ON home_selection_snapshot FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable home_selection_snapshot';
CREATE TRIGGER home_selection_no_delete BEFORE DELETE ON home_selection_snapshot FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable home_selection_snapshot';
