-- Images live in immutable object storage; only cache metadata and a fenced I/O lease live in SQL.
ALTER TABLE referral_code
 ADD COLUMN image_key VARCHAR(64) NULL,
 ADD COLUMN image_version VARCHAR(128) NULL,
 ADD COLUMN image_bytes BIGINT NULL,
 ADD COLUMN image_app_id VARCHAR(128) NULL,
 ADD COLUMN image_lease_token VARCHAR(48) COLLATE ascii_bin NULL,
 ADD COLUMN image_lease_until DATETIME(6) NULL,
 ADD KEY ix_referral_reuse(product_id,distributor_id,enabled,expires_at),
 ADD CONSTRAINT ck_referral_image CHECK (
   (image_key IS NULL AND image_version IS NULL AND image_bytes IS NULL AND image_app_id IS NULL)
   OR (image_key IS NOT NULL AND image_version IS NOT NULL AND image_bytes IS NOT NULL
       AND image_bytes BETWEEN 1 AND 1048576 AND image_app_id IS NOT NULL));
