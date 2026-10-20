-- Anchors preserve foreign-key integrity without order -> customer/catalog lock inversion.
-- Create subjects under customer lock before checkout creates an order. Consumers never insert them.
CREATE TABLE entitlement_subject (
 user_id BIGINT UNSIGNED PRIMARY KEY, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_entitlement_subject_user FOREIGN KEY(user_id) REFERENCES user_account(id)
);
INSERT INTO entitlement_subject(user_id) SELECT id FROM user_account;
CREATE TABLE entitlement_course_target (
 course_id BIGINT UNSIGNED PRIMARY KEY, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_entitlement_target_course FOREIGN KEY(course_id) REFERENCES course(id)
);
INSERT INTO entitlement_course_target(course_id) SELECT id FROM course;

CREATE TABLE entitlement (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, order_item_id BIGINT UNSIGNED NULL,
 source_key VARCHAR(160) NOT NULL UNIQUE, entitlement_type VARCHAR(24) NOT NULL, target_id BIGINT UNSIGNED NULL,
 paid_at DATETIME(6) NOT NULL, starts_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_entitlement_item(order_item_id), KEY ix_entitlement_user(user_id,entitlement_type,target_id),
 CONSTRAINT fk_entitlement_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_entitlement_item FOREIGN KEY(order_item_id) REFERENCES order_item(id),
 CONSTRAINT fk_entitlement_course_target FOREIGN KEY(target_id) REFERENCES entitlement_course_target(course_id),
 CHECK(entitlement_type IN ('COURSE_VIEW','MEMBER_PAPER')),
 CHECK((entitlement_type='COURSE_VIEW' AND target_id IS NOT NULL) OR (entitlement_type='MEMBER_PAPER' AND target_id IS NULL)),
 CHECK(expires_at IS NULL OR expires_at > starts_at),
 CHECK(order_item_id IS NOT NULL OR source_key LIKE 'DEV-%')
);
CREATE TABLE membership_grant (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, entitlement_id BIGINT UNSIGNED NOT NULL UNIQUE,
 user_id BIGINT UNSIGNED NOT NULL, order_item_id BIGINT UNSIGNED NULL UNIQUE,
 paid_at DATETIME(6) NOT NULL, duration_months INT NOT NULL,
 original_start_at DATETIME(6) NOT NULL, original_end_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_membership_replay(user_id,paid_at,id),
 CONSTRAINT fk_membership_grant_entitlement FOREIGN KEY(entitlement_id) REFERENCES entitlement(id),
 CONSTRAINT fk_membership_grant_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_membership_grant_item FOREIGN KEY(order_item_id) REFERENCES order_item(id),
 CHECK(duration_months IN (6,12)), CHECK(original_end_at > original_start_at)
);
CREATE TABLE entitlement_event (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, source_key VARCHAR(160) NOT NULL,
 event_key VARCHAR(200) NOT NULL UNIQUE, event_type VARCHAR(24) NOT NULL, order_item_id BIGINT UNSIGNED NULL,
 detail_json JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_entitlement_revocation(source_key,event_type),
 CONSTRAINT fk_entitlement_event_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_entitlement_event_item FOREIGN KEY(order_item_id) REFERENCES order_item(id),
 CHECK(event_type IN ('GRANT','REVOKE','REPROJECT'))
);
CREATE TABLE membership_record (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL UNIQUE, level_code VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
 current_start_at DATETIME(6) NULL, expires_at DATETIME(6) NULL, version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_membership_record_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id)
);
CREATE TABLE membership_interval (
 grant_id BIGINT UNSIGNED PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, starts_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_membership_interval_grant FOREIGN KEY(grant_id) REFERENCES membership_grant(id),
 CONSTRAINT fk_membership_interval_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 KEY ix_membership_interval_access(user_id,starts_at,expires_at), CHECK(expires_at > starts_at)
);
CREATE TABLE learning_progress (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, chapter_id BIGINT UNSIGNED NOT NULL,
 position_seconds INT NOT NULL, completed BOOLEAN NOT NULL, last_learned_at DATETIME(6) NOT NULL,
 version BIGINT UNSIGNED NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_learning_owner_chapter(user_id,chapter_id),
 CONSTRAINT fk_learning_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_learning_chapter FOREIGN KEY(chapter_id) REFERENCES course_chapter(id), CHECK(position_seconds >= 0)
);
CREATE TABLE content_capability (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, token_hash CHAR(64) NOT NULL UNIQUE, user_id BIGINT UNSIGNED NULL,
 asset_id BIGINT UNSIGNED NOT NULL, paper_id BIGINT UNSIGNED NULL, chapter_id BIGINT UNSIGNED NULL,
 action VARCHAR(16) NOT NULL, storage_key VARCHAR(255) NOT NULL, sealed_version VARCHAR(255) NOT NULL,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_capability_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_capability_asset FOREIGN KEY(asset_id) REFERENCES file_asset(id),
 CONSTRAINT fk_capability_paper FOREIGN KEY(paper_id) REFERENCES paper(id),
 CONSTRAINT fk_capability_chapter FOREIGN KEY(chapter_id) REFERENCES course_chapter(id),
 CHECK(action IN ('PREVIEW','DOWNLOAD','PLAY')),
 CHECK((paper_id IS NOT NULL AND chapter_id IS NULL AND action IN ('PREVIEW','DOWNLOAD')) OR (paper_id IS NULL AND chapter_id IS NOT NULL AND action='PLAY'))
);
CREATE TABLE download_audit (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, asset_id BIGINT UNSIGNED NOT NULL, paper_id BIGINT UNSIGNED NOT NULL,
 action VARCHAR(16) NOT NULL, result VARCHAR(32) NOT NULL, request_digest CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_download_subject FOREIGN KEY(user_id) REFERENCES entitlement_subject(user_id),
 CONSTRAINT fk_download_asset FOREIGN KEY(asset_id) REFERENCES file_asset(id),
 CONSTRAINT fk_download_paper FOREIGN KEY(paper_id) REFERENCES paper(id),
 CHECK(action IN ('PREVIEW','DOWNLOAD')), CHECK(result='AUTHORIZED')
);

CREATE TRIGGER entitlement_no_update BEFORE UPDATE ON entitlement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement grants are immutable';
CREATE TRIGGER entitlement_no_delete BEFORE DELETE ON entitlement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement grants are immutable';
CREATE TRIGGER membership_grant_no_update BEFORE UPDATE ON membership_grant FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Membership grants are immutable';
CREATE TRIGGER membership_grant_no_delete BEFORE DELETE ON membership_grant FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Membership grants are immutable';
CREATE TRIGGER entitlement_event_no_update BEFORE UPDATE ON entitlement_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement events are immutable';
CREATE TRIGGER entitlement_event_no_delete BEFORE DELETE ON entitlement_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement events are immutable';
CREATE TRIGGER entitlement_target_no_update BEFORE UPDATE ON entitlement_course_target FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement targets are immutable';
CREATE TRIGGER entitlement_target_no_delete BEFORE DELETE ON entitlement_course_target FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement targets are immutable';
CREATE TRIGGER download_audit_no_update BEFORE UPDATE ON download_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Authorization audit is immutable';
CREATE TRIGGER download_audit_no_delete BEFORE DELETE ON download_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Authorization audit is immutable';
