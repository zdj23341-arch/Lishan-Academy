CREATE TABLE file_asset (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_type VARCHAR(16) NOT NULL, owner_id BIGINT UNSIGNED NOT NULL, purpose VARCHAR(32) NOT NULL, storage_key VARCHAR(64) NOT NULL UNIQUE, sealed_version VARCHAR(128) NULL, original_name VARCHAR(200) NOT NULL, extension VARCHAR(8) NOT NULL, mime VARCHAR(100) NOT NULL, expected_bytes BIGINT NOT NULL, bytes BIGINT NULL, sha256 VARCHAR(64) NULL, status VARCHAR(20) NOT NULL, scan_status VARCHAR(40) NOT NULL, upload_token_hash VARCHAR(64) NOT NULL, upload_expires_at DATETIME(6) NOT NULL, seed_key VARCHAR(80) NULL UNIQUE, CHECK(expected_bytes>0), CHECK(status IN ('PENDING','UPLOADED','READY','REJECTED','DISABLED')),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL, type VARCHAR(20) NOT NULL, parent_id BIGINT UNSIGNED NULL, sort_no INT NOT NULL, enabled BOOLEAN NOT NULL, seed_key VARCHAR(80) NULL UNIQUE, FOREIGN KEY(parent_id) REFERENCES category(id), CHECK(type IN ('COURSE','BOOK','MEMBERSHIP')),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(20) NOT NULL, title VARCHAR(200) NOT NULL, subtitle VARCHAR(500) NULL, cover_asset_id BIGINT UNSIGNED NULL, price_fen BIGINT NOT NULL, original_price_fen BIGINT NULL, status VARCHAR(20) NOT NULL, detail_json JSON NOT NULL, sort_no INT NOT NULL, quantity_limit INT NULL, shipping_summary VARCHAR(500) NULL, seed_key VARCHAR(80) NULL UNIQUE, FOREIGN KEY(cover_asset_id) REFERENCES file_asset(id), CHECK(price_fen BETWEEN 0 AND 100000000), CHECK(original_price_fen IS NULL OR original_price_fen BETWEEN 0 AND 100000000), CHECK(type IN ('COURSE','BOOK','MEMBERSHIP')), CHECK(status IN ('DRAFT','ON_SALE','OFF_SALE')), CHECK((type='BOOK' AND quantity_limit BETWEEN 1 AND 99 AND shipping_summary IS NOT NULL) OR (type<>'BOOK' AND quantity_limit IS NULL AND shipping_summary IS NULL)), INDEX ix_product_query(status,type,created_at,id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book_inventory (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT UNSIGNED NOT NULL UNIQUE, on_hand INT NOT NULL, reserved INT NOT NULL, FOREIGN KEY(product_id) REFERENCES product(id), CHECK(on_hand>=reserved AND reserved>=0),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_ledger (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT UNSIGNED NOT NULL, order_item_id BIGINT UNSIGNED NULL, event_type VARCHAR(40) NOT NULL, on_hand_delta INT NOT NULL, reserved_delta INT NOT NULL, on_hand_before INT NOT NULL, on_hand_after INT NOT NULL, reserved_before INT NOT NULL, reserved_after INT NOT NULL, business_key VARCHAR(128) NOT NULL UNIQUE, reason VARCHAR(500) NOT NULL, FOREIGN KEY(product_id) REFERENCES product(id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE course (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chapter_revision BIGINT NOT NULL DEFAULT 0,
    product_id BIGINT UNSIGNED NOT NULL UNIQUE, validity_type VARCHAR(20) NOT NULL, validity_days INT NULL, teacher_name VARCHAR(200) NULL, FOREIGN KEY(product_id) REFERENCES product(id), CHECK((validity_type='DAYS' AND validity_days>0) OR (validity_type='PERMANENT' AND validity_days IS NULL)),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE course_chapter (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT UNSIGNED NOT NULL, title VARCHAR(200) NOT NULL, sort_no INT NOT NULL, video_asset_id BIGINT UNSIGNED NULL, duration_seconds INT NOT NULL, is_trial BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL, FOREIGN KEY(course_id) REFERENCES course(id), FOREIGN KEY(video_asset_id) REFERENCES file_asset(id), CHECK(duration_seconds>0), INDEX ix_chapter_sort(course_id,sort_no,id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE membership_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT UNSIGNED NOT NULL UNIQUE, level_code VARCHAR(20) NOT NULL, duration_months INT NOT NULL, status VARCHAR(20) NOT NULL, active_duration INT GENERATED ALWAYS AS (IF(status='ENABLED',duration_months,NULL)) STORED, UNIQUE KEY uk_active_duration(active_duration), FOREIGN KEY(product_id) REFERENCES product(id), CHECK(duration_months IN (6,12)), CHECK(level_code='STANDARD'), CHECK(status IN ('ENABLED','DISABLED')),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paper (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attachment_revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(200) NOT NULL, stage VARCHAR(40) NOT NULL, grade VARCHAR(20) NOT NULL, subject VARCHAR(40) NOT NULL, year INT NULL, status VARCHAR(20) NOT NULL, visibility VARCHAR(20) NOT NULL, cover_asset_id BIGINT UNSIGNED NULL, description_json JSON NOT NULL, seed_key VARCHAR(80) NULL UNIQUE, FOREIGN KEY(cover_asset_id) REFERENCES file_asset(id), CHECK(status IN ('DRAFT','PUBLISHED','UNPUBLISHED')), CHECK(visibility='MEMBER'), INDEX ix_paper_query(status,grade,year,id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paper_attachment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT UNSIGNED NOT NULL, asset_id BIGINT UNSIGNED NOT NULL, title VARCHAR(200) NOT NULL, sort_no INT NOT NULL, FOREIGN KEY(paper_id) REFERENCES paper(id), FOREIGN KEY(asset_id) REFERENCES file_asset(id), UNIQUE KEY uk_paper_asset(paper_id,asset_id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE article (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL, cover_asset_id BIGINT UNSIGNED NULL, body_json JSON NOT NULL, status VARCHAR(20) NOT NULL, published_at DATETIME(6) NULL, seed_key VARCHAR(80) NULL UNIQUE, FOREIGN KEY(cover_asset_id) REFERENCES file_asset(id), CHECK(status IN ('DRAFT','PUBLISHED','UNPUBLISHED')), INDEX ix_article_public(status,published_at),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_draft (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_banner (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_key VARCHAR(20) NOT NULL UNIQUE, title VARCHAR(200) NOT NULL, asset_id BIGINT UNSIGNED NOT NULL, target_type VARCHAR(30) NOT NULL, target_id BIGINT UNSIGNED NULL, filters_json JSON NOT NULL, sort_no INT NOT NULL, enabled BOOLEAN NOT NULL, FOREIGN KEY(asset_id) REFERENCES file_asset(id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_shortcut (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_key VARCHAR(20) NOT NULL UNIQUE, label VARCHAR(200) NOT NULL, asset_id BIGINT UNSIGNED NOT NULL, target_type VARCHAR(30) NOT NULL, target_id BIGINT UNSIGNED NULL, filters_json JSON NOT NULL, sort_no INT NOT NULL, enabled BOOLEAN NOT NULL, FOREIGN KEY(asset_id) REFERENCES file_asset(id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_section (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_key VARCHAR(20) NOT NULL UNIQUE, type VARCHAR(30) NOT NULL, title VARCHAR(200) NOT NULL, more_target_json JSON NULL, article_id BIGINT UNSIGNED NULL, sort_no INT NOT NULL, enabled BOOLEAN NOT NULL, FOREIGN KEY(article_id) REFERENCES article(id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_section_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    section_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL, sort_no INT NOT NULL, FOREIGN KEY(section_id) REFERENCES home_section(id), FOREIGN KEY(product_id) REFERENCES product(id), UNIQUE KEY uk_section_order(section_id,sort_no), UNIQUE KEY uk_section_product(section_id,product_id),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE home_layout (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revision BIGINT NOT NULL UNIQUE, modules_json JSON NOT NULL, status VARCHAR(20) NOT NULL, published_at DATETIME(6) NOT NULL, published_by BIGINT UNSIGNED NULL, seed_key VARCHAR(80) NULL UNIQUE, CHECK(published_by IS NOT NULL OR seed_key IS NOT NULL), FOREIGN KEY(published_by) REFERENCES admin_user(id), CHECK(status='PUBLISHED'),
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dev_seed_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    seed_key VARCHAR(80) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_category (product_id BIGINT UNSIGNED NOT NULL, category_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(product_id,category_id), FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(category_id) REFERENCES category(id)) ENGINE=InnoDB;
INSERT INTO home_draft(id,revision,created_at,updated_at,version) VALUES(1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);
CREATE TRIGGER inventory_ledger_no_update BEFORE UPDATE ON inventory_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable inventory_ledger';
CREATE TRIGGER inventory_ledger_no_delete BEFORE DELETE ON inventory_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable inventory_ledger';
CREATE TRIGGER home_layout_no_update BEFORE UPDATE ON home_layout FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable home_layout';
CREATE TRIGGER home_layout_no_delete BEFORE DELETE ON home_layout FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable home_layout';
