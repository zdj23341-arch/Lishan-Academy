ALTER TABLE home_section
    ADD COLUMN selection_mode VARCHAR(12) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN display_limit INT NOT NULL DEFAULT 4,
    ADD COLUMN pinned_ids JSON NULL,
    ADD CONSTRAINT ck_home_section_selection_mode CHECK (selection_mode IN ('MANUAL','AUTO')),
    ADD CONSTRAINT ck_home_section_display_limit CHECK (display_limit BETWEEN 1 AND 50);

UPDATE home_section
SET pinned_ids = JSON_ARRAY() WHERE pinned_ids IS NULL;

CREATE INDEX ix_product_public_newest ON product(status, type, created_at, id);
CREATE INDEX ix_paper_public_newest ON paper(status, created_at, id);

