CREATE INDEX ix_product_first_publication ON product(status,type,first_published_at,id);
CREATE INDEX ix_paper_first_publication ON paper(status,first_published_at,id);

-- V23 allowed already-published INSERTs without a timestamp. Preserve their
-- creation time rather than treating migration or the next edit as publication.
DROP TRIGGER product_first_publish;
CREATE TRIGGER product_first_publish BEFORE UPDATE ON product FOR EACH ROW
SET NEW.first_published_at = CASE
    WHEN OLD.first_published_at IS NOT NULL THEN OLD.first_published_at
    WHEN OLD.status IN ('ON_SALE','OFF_SALE') THEN OLD.created_at
    WHEN NEW.status='ON_SALE' THEN UTC_TIMESTAMP(6) ELSE NULL END;
DROP TRIGGER paper_first_publish;
CREATE TRIGGER paper_first_publish BEFORE UPDATE ON paper FOR EACH ROW
SET NEW.first_published_at = CASE
    WHEN OLD.first_published_at IS NOT NULL THEN OLD.first_published_at
    WHEN OLD.status IN ('PUBLISHED','UNPUBLISHED') THEN OLD.created_at
    WHEN NEW.status='PUBLISHED' THEN UTC_TIMESTAMP(6) ELSE NULL END;
UPDATE product SET first_published_at=created_at
WHERE first_published_at IS NULL AND status IN ('ON_SALE','OFF_SALE');
UPDATE paper SET first_published_at=created_at
WHERE first_published_at IS NULL AND status IN ('PUBLISHED','UNPUBLISHED');

CREATE TRIGGER product_first_publish_insert BEFORE INSERT ON product FOR EACH ROW
SET NEW.first_published_at = CASE WHEN NEW.status IN ('ON_SALE','OFF_SALE')
    THEN COALESCE(NEW.first_published_at,NEW.created_at,UTC_TIMESTAMP(6)) ELSE NULL END;

CREATE TRIGGER paper_first_publish_insert BEFORE INSERT ON paper FOR EACH ROW
SET NEW.first_published_at = CASE WHEN NEW.status IN ('PUBLISHED','UNPUBLISHED')
    THEN COALESCE(NEW.first_published_at,NEW.created_at,UTC_TIMESTAMP(6)) ELSE NULL END;
