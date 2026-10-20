-- Permanent receipts commit with the linked records; retries never recreate a committed draft.
CREATE TABLE course_workflow_operation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT UNSIGNED NOT NULL,
    operation_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    course_id BIGINT UNSIGNED NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_course_workflow_operation(actor_id, operation_key),
    FOREIGN KEY (actor_id) REFERENCES admin_user(id),
    FOREIGN KEY (course_id) REFERENCES course(id),
    CHECK (action IN ('CREATE','SAVE','PUBLISH','CHAPTER_CREATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TRIGGER course_workflow_operation_no_update BEFORE UPDATE ON course_workflow_operation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable course workflow receipt';
CREATE TRIGGER course_workflow_operation_no_delete BEFORE DELETE ON course_workflow_operation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable course workflow receipt';
