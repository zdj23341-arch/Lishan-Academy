-- 2026-10-11 education commerce handoff contract.
ALTER TABLE course
    ADD COLUMN duration_minutes INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_course_duration_minutes CHECK (duration_minutes >= 0);

ALTER TABLE paper
    ADD COLUMN school_stage VARCHAR(40) NULL AFTER stage,
    ADD COLUMN paper_type VARCHAR(40) NOT NULL DEFAULT 'PRACTICE' AFTER subject,
    ADD COLUMN summary VARCHAR(1000) NOT NULL DEFAULT '' AFTER description_json;

UPDATE paper SET school_stage = stage WHERE school_stage IS NULL;
ALTER TABLE paper
    MODIFY COLUMN school_stage VARCHAR(40) NOT NULL;

CREATE INDEX ix_paper_contract_query
    ON paper(status, school_stage, paper_type, grade, subject, year, id);

CREATE TABLE home_section_paper (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    section_id BIGINT UNSIGNED NOT NULL,
    paper_id BIGINT UNSIGNED NOT NULL,
    sort_no INT NOT NULL,
    FOREIGN KEY(section_id) REFERENCES home_section(id),
    FOREIGN KEY(paper_id) REFERENCES paper(id),
    UNIQUE KEY uk_section_paper_order(section_id, sort_no),
    UNIQUE KEY uk_section_paper(section_id, paper_id),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
