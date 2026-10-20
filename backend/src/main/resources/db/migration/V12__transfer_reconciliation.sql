CREATE TABLE withdrawal_reconciliation (
 withdrawal_id BIGINT UNSIGNED PRIMARY KEY, distributor_id BIGINT UNSIGNED NOT NULL,
 status VARCHAR(24) NOT NULL, success_observed BOOLEAN NOT NULL,
 observed_paid_out_fen BIGINT NOT NULL, last_query_state VARCHAR(64) NULL,
 next_query_at DATETIME(6) NOT NULL, query_count INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(withdrawal_id) REFERENCES withdrawal_request(id), FOREIGN KEY(distributor_id) REFERENCES commission_account(distributor_id),
 CHECK(status IN ('PENDING_QUERY','NEEDS_REVIEW')), CHECK(observed_paid_out_fen>=0)
);
