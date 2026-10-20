-- Additive metadata: existing articles remain unassigned; no sample author/category backfill.
ALTER TABLE article
    ADD COLUMN author_name VARCHAR(100) NULL,
    ADD COLUMN category_name VARCHAR(80) NULL;
