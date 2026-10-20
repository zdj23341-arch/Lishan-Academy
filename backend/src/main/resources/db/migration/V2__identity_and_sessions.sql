CREATE TABLE user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nickname VARCHAR(200) NOT NULL,
    avatar_url VARCHAR(512) NULL,
    phone_cipher TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CHECK (status IN ('ACTIVE','DISABLED','ANONYMIZED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_wechat_identity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    app_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    openid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    unionid VARCHAR(128) COLLATE utf8mb4_bin NULL,
    last_login_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_identity_app_openid (app_id,openid), INDEX ix_identity_unionid (unionid),
    CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_name VARCHAR(100) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL, status VARCHAR(32) NOT NULL,
    password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE admin_user_role (
    admin_user_id BIGINT UNSIGNED NOT NULL, role_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(admin_user_id,role_id),
    FOREIGN KEY (admin_user_id) REFERENCES admin_user(id), FOREIGN KEY (role_id) REFERENCES admin_role(id)
) ENGINE=InnoDB;
CREATE TABLE admin_role_permission (
    role_id BIGINT UNSIGNED NOT NULL, permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(role_id,permission_id),
    FOREIGN KEY (role_id) REFERENCES admin_role(id), FOREIGN KEY (permission_id) REFERENCES admin_permission(id)
) ENGINE=InnoDB;
INSERT INTO admin_role (code,created_at,updated_at) VALUES
 ('CONTENT_EDITOR',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('ORDER_OPERATOR',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
 ('FINANCE_OPERATOR',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('DISTRIBUTION_OPERATOR',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
 ('ANALYST',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('SUPER_ADMIN',UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO admin_permission (code,created_at,updated_at) VALUES
 ('CONTENT_EDIT',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('ORDER_OPERATE',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
 ('FINANCE_OPERATE',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('DISTRIBUTION_OPERATE',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
 ('ANALYTICS_READ',UTC_TIMESTAMP(),UTC_TIMESTAMP()),('AUDIT_READ',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
 ('SETTINGS_MANAGE',UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO admin_role_permission SELECT r.id,p.id FROM admin_role r CROSS JOIN admin_permission p
 WHERE r.code='SUPER_ADMIN' OR (r.code='CONTENT_EDITOR' AND p.code='CONTENT_EDIT')
 OR (r.code='ORDER_OPERATOR' AND p.code='ORDER_OPERATE') OR (r.code='FINANCE_OPERATOR' AND p.code='FINANCE_OPERATE')
 OR (r.code='DISTRIBUTION_OPERATOR' AND p.code='DISTRIBUTION_OPERATE') OR (r.code='ANALYST' AND p.code='ANALYTICS_READ');

CREATE TABLE login_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    token_hash VARCHAR(64) COLLATE ascii_bin NOT NULL UNIQUE,
    actor_type VARCHAR(16) NOT NULL, actor_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NULL, admin_user_id BIGINT UNSIGNED NULL,
    expires_at DATETIME(6) NOT NULL, revoked_at DATETIME(6) NULL,
    device_hash VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    INDEX ix_session_actor (actor_type,actor_id), INDEX ix_session_expiry (expires_at),
    FOREIGN KEY(user_id) REFERENCES user_account(id), FOREIGN KEY(admin_user_id) REFERENCES admin_user(id),
    CHECK ((actor_type='CUSTOMER' AND user_id=actor_id AND user_id IS NOT NULL AND admin_user_id IS NULL)
        OR (actor_type='ADMIN' AND admin_user_id=actor_id AND admin_user_id IS NOT NULL AND user_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE login_rate_limit (
    bucket_hash VARCHAR(64) COLLATE ascii_bin NOT NULL PRIMARY KEY,
    window_start DATETIME(6) NOT NULL, attempts INT NOT NULL,
    CHECK (attempts>=0)
) ENGINE=InnoDB DEFAULT CHARSET=ascii;
