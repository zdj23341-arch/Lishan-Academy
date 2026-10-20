-- Catalog writers acquire this row before any category/product/plan entity locks.
CREATE TABLE catalog_consistency_lock (
    id BIGINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_catalog_consistency_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO catalog_consistency_lock (id) VALUES (1);
