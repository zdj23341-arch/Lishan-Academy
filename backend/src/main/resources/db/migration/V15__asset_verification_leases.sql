ALTER TABLE file_asset
    ADD COLUMN processing_token VARCHAR(64) NULL,
    ADD COLUMN processing_until DATETIME(6) NULL;
