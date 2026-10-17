-- IM-A6 / ADR 0009: async inbox entry fan-out jobs (200 < N ≤ 2000).
-- IM-A6 / ADR 0009：站内信异步写扩散任务（200 < N ≤ 2000）。
-- Truth remains inbox_entry; this queue only stages recipients until entries are written.
-- 真相仍是 inbox_entry；本队列表仅暂存收件人直至写入 entry。

CREATE TABLE inbox_fanout_job (
    job_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_count INT NOT NULL,
    done_count INT NOT NULL,
    error_message VARCHAR(1024),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (job_id)
);

CREATE INDEX inbox_fanout_job_status_created
    ON inbox_fanout_job (status, created_at);

CREATE INDEX inbox_fanout_job_message
    ON inbox_fanout_job (message_id);

CREATE TABLE inbox_fanout_recipient (
    job_id VARCHAR(64) NOT NULL,
    recipient_realm VARCHAR(16) NOT NULL,
    recipient_subject_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (job_id, recipient_realm, recipient_subject_id)
);

CREATE INDEX inbox_fanout_recipient_job
    ON inbox_fanout_recipient (job_id);
