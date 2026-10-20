CREATE TABLE user_address (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, name_cipher TEXT NOT NULL, phone_cipher TEXT NOT NULL, detail_cipher TEXT NOT NULL,
 province_code VARCHAR(6) NOT NULL, city_code VARCHAR(6) NOT NULL, district_code VARCHAR(6) NOT NULL,
 is_default BOOLEAN NOT NULL, deleted BOOLEAN NOT NULL,
 default_user BIGINT UNSIGNED GENERATED ALWAYS AS (IF(is_default AND NOT deleted,user_id,NULL)) STORED UNIQUE,
 FOREIGN KEY(user_id) REFERENCES user_account(id), INDEX ix_address_user(user_id,deleted),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipping_rule (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 province_code VARCHAR(6) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL, freight_fen BIGINT NOT NULL, free_threshold_fen BIGINT NULL,
 CHECK(freight_fen BETWEEN 0 AND 100000000), CHECK(free_threshold_fen IS NULL OR free_threshold_fen BETWEEN 0 AND 10000000000),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_quote (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL, address_id BIGINT UNSIGNED NULL, address_version BIGINT NULL,
 shipping_rule_id BIGINT UNSIGNED NULL, shipping_rule_version BIGINT NULL, quantity INT NOT NULL, product_version BIGINT NOT NULL, terms_version BIGINT NULL,
 goods_amount_fen BIGINT NOT NULL, freight_amount_fen BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL, snapshot_json TEXT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES user_account(id), FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(address_id) REFERENCES user_address(id), FOREIGN KEY(shipping_rule_id) REFERENCES shipping_rule(id), CHECK(quantity BETWEEN 1 AND 99),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE store_order (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_no VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT UNSIGNED NOT NULL, quote_id BIGINT UNSIGNED NOT NULL UNIQUE, channel VARCHAR(20) NOT NULL,
 order_status VARCHAR(24) NOT NULL, refund_status VARCHAR(24) NOT NULL, fulfillment_status VARCHAR(24) NOT NULL,
 goods_amount_fen BIGINT NOT NULL, freight_amount_fen BIGINT NOT NULL, payable_amount_fen BIGINT NOT NULL, refunded_goods_fen BIGINT NOT NULL DEFAULT 0, refunded_freight_fen BIGINT NOT NULL DEFAULT 0,
 expires_at DATETIME(6) NOT NULL, paid_at DATETIME(6) NULL, completed_at DATETIME(6) NULL, cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 successful_payment_id BIGINT UNSIGNED NULL, active_aftersale_id BIGINT UNSIGNED NULL, attribution_snapshot_json TEXT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES user_account(id), FOREIGN KEY(quote_id) REFERENCES order_quote(id),
 CHECK(order_status IN ('PENDING_PAYMENT','PAID','COMPLETED','CANCELLED')),
 CHECK(goods_amount_fen>=0 AND freight_amount_fen>=0 AND payable_amount_fen=goods_amount_fen+freight_amount_fen),
 CHECK(refunded_goods_fen BETWEEN 0 AND goods_amount_fen AND refunded_freight_fen BETWEEN 0 AND freight_amount_fen),
 INDEX ix_order_user(user_id,created_at,id), INDEX ix_order_expiry(order_status,expires_at),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_item (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL UNIQUE, product_id BIGINT UNSIGNED NOT NULL, quantity INT NOT NULL, unit_price_fen BIGINT NOT NULL, goods_paid_fen BIGINT NOT NULL, goods_refunded_fen BIGINT NOT NULL DEFAULT 0,
 type VARCHAR(20) NOT NULL, title_snapshot VARCHAR(200) NOT NULL, stock_state VARCHAR(20) NOT NULL, restored_quantity INT NOT NULL DEFAULT 0, fulfillment_snapshot_json TEXT NOT NULL,
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(product_id) REFERENCES product(id), CHECK(quantity BETWEEN 1 AND 99), CHECK(goods_refunded_fen BETWEEN 0 AND goods_paid_fen), CHECK(restored_quantity BETWEEN 0 AND quantity),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_address_snapshot (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL UNIQUE, name_cipher TEXT NOT NULL, phone_cipher TEXT NOT NULL, detail_cipher TEXT NOT NULL,
 province_code VARCHAR(6) NOT NULL, city_code VARCHAR(6) NOT NULL, district_code VARCHAR(6) NOT NULL, FOREIGN KEY(order_id) REFERENCES store_order(id),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_transaction (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL, out_trade_no VARCHAR(64) NOT NULL, channel VARCHAR(20) NOT NULL, channel_txn_id VARCHAR(100) NULL, state VARCHAR(20) NOT NULL, amount_fen BIGINT NOT NULL, paid_at DATETIME(6) NULL, params_json VARCHAR(4000) NULL,
 active_order BIGINT UNSIGNED GENERATED ALWAYS AS (IF(state IN ('CREATED','PROCESSING','UNKNOWN'),order_id,NULL)) STORED UNIQUE,
 UNIQUE KEY uk_payment_number(channel,out_trade_no), UNIQUE KEY uk_payment_external(channel,channel_txn_id), FOREIGN KEY(order_id) REFERENCES store_order(id), CHECK(amount_fen>0),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE aftersale_case (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 case_no VARCHAR(64) NOT NULL UNIQUE, order_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, type VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, reason VARCHAR(500) NOT NULL,
 requested_goods_fen BIGINT NOT NULL, requested_freight_fen BIGINT NOT NULL, approved_goods_fen BIGINT NULL, approved_freight_fen BIGINT NULL, returned_quantity INT NOT NULL DEFAULT 0, received_quantity INT NOT NULL DEFAULT 0,
 return_address_cipher TEXT NULL, return_carrier_code VARCHAR(40) NULL, return_tracking_no VARCHAR(100) NULL, received_at DATETIME(6) NULL,
 active_order BIGINT UNSIGNED GENERATED ALWAYS AS (IF(status IN ('REQUESTED','RETURN_REQUIRED','RETURNING','PROCESSING'),order_id,NULL)) STORED UNIQUE,
 FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(user_id) REFERENCES user_account(id), CHECK(requested_goods_fen>=0 AND requested_freight_fen>=0), CHECK(received_quantity BETWEEN 0 AND returned_quantity),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE aftersale_evidence (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 aftersale_id BIGINT UNSIGNED NOT NULL, asset_id BIGINT UNSIGNED NOT NULL,
 FOREIGN KEY(aftersale_id) REFERENCES aftersale_case(id), FOREIGN KEY(asset_id) REFERENCES file_asset(id), UNIQUE KEY uk_aftersale_evidence(aftersale_id,asset_id),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund_transaction (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 aftersale_id BIGINT UNSIGNED NULL UNIQUE, order_id BIGINT UNSIGNED NOT NULL, payment_id BIGINT UNSIGNED NULL, out_refund_no VARCHAR(64) NOT NULL UNIQUE, channel_refund_id VARCHAR(100) NULL, channel VARCHAR(20) NOT NULL,
 goods_fen BIGINT NOT NULL, freight_fen BIGINT NOT NULL, state VARCHAR(20) NOT NULL, reason VARCHAR(500) NOT NULL, success_at DATETIME(6) NULL, restock_quantity INT NOT NULL, exceptional BOOLEAN NOT NULL,
 exceptional_payment BIGINT UNSIGNED GENERATED ALWAYS AS (IF(exceptional,payment_id,NULL)) STORED UNIQUE,
 UNIQUE KEY uk_refund_external(channel,channel_refund_id), FOREIGN KEY(aftersale_id) REFERENCES aftersale_case(id), FOREIGN KEY(order_id) REFERENCES store_order(id), FOREIGN KEY(payment_id) REFERENCES payment_transaction(id), CHECK(goods_fen>=0 AND freight_fen>=0), INDEX ix_refund_recovery(state,updated_at),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipment (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL UNIQUE, carrier_code VARCHAR(40) NOT NULL, tracking_no VARCHAR(100) NOT NULL, shipped_at DATETIME(6) NOT NULL, received_at DATETIME(6) NULL,
 status VARCHAR(20) NOT NULL, next_sync_at DATETIME(6) NOT NULL, simulated BOOLEAN NOT NULL, events_json TEXT NOT NULL, FOREIGN KEY(order_id) REFERENCES store_order(id), INDEX ix_shipment_sync(status,next_sync_at),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_type VARCHAR(40) NOT NULL, business_key VARCHAR(128) NOT NULL, payload_json TEXT NOT NULL,
 status VARCHAR(20) NOT NULL, retry_count INT NOT NULL, next_run_at DATETIME(6) NOT NULL, locked_until DATETIME(6) NULL, lease_token VARCHAR(64) NULL, last_error VARCHAR(200) NULL,
 UNIQUE KEY uk_outbox_business(task_type,business_key), INDEX ix_outbox_due(status,next_run_at,locked_until),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE callback_inbox (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 provider VARCHAR(20) NOT NULL, event_id VARCHAR(100) NOT NULL, resource_type VARCHAR(20) NOT NULL, signature_valid BOOLEAN NOT NULL, payload_encrypted TEXT NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, processed_at DATETIME(6) NULL, retry_count INT NOT NULL, error_code VARCHAR(100) NULL,
 UNIQUE KEY uk_inbox_event(provider,event_id),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE inventory_ledger ADD CONSTRAINT fk_inventory_order_item FOREIGN KEY(order_item_id) REFERENCES order_item(id);
ALTER TABLE store_order ADD FOREIGN KEY(successful_payment_id) REFERENCES payment_transaction(id), ADD FOREIGN KEY(active_aftersale_id) REFERENCES aftersale_case(id);
CREATE TABLE dev_payment_state (business_no VARCHAR(64) NOT NULL PRIMARY KEY, kind VARCHAR(20) NOT NULL, scenario VARCHAR(30) NOT NULL, channel_id VARCHAR(100) NOT NULL, changed_at DATETIME(6) NOT NULL) ENGINE=InnoDB;
ALTER TABLE order_quote ADD order_id BIGINT UNSIGNED NULL UNIQUE, ADD FOREIGN KEY(order_id) REFERENCES store_order(id);

ALTER TABLE payment_transaction ADD refund_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment_transaction ADD app_id VARCHAR(100) NOT NULL, ADD merchant_id VARCHAR(100) NOT NULL;

ALTER TABLE order_quote ADD token_hash VARCHAR(64) NOT NULL UNIQUE, ADD INDEX ix_quote_expiry(expires_at);
