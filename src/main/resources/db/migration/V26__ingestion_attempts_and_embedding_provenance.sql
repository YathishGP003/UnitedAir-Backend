-- Durable FR-032 ingestion-attempt ledger. This row is created before validation in
-- a REQUIRES_NEW transaction, so malformed files are still auditable.
CREATE TABLE kb_ingestion_attempt (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_uuid        CHAR(36)      NOT NULL,
    triggered_by        BIGINT        NULL,
    source_filename     VARCHAR(255)  NOT NULL,
    status              VARCHAR(24)   NOT NULL DEFAULT 'STARTED',
    failure_phase       VARCHAR(32)   NULL,
    failure_reason      VARCHAR(1000) NULL,
    document_id         BIGINT        NULL,
    version_id          BIGINT        NULL,
    job_uuid            CHAR(36)      NULL,
    chunks_created      INT           NOT NULL DEFAULT 0,
    ingestion_time_ms   BIGINT        NULL,
    started_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at         TIMESTAMP(6)  NULL,
    UNIQUE KEY uq_kb_ingestion_attempt_uuid (attempt_uuid),
    KEY idx_kb_ingestion_attempt_status (status),
    KEY idx_kb_ingestion_attempt_started (started_at),
    CONSTRAINT fk_kb_attempt_user
        FOREIGN KEY (triggered_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_kb_attempt_document
        FOREIGN KEY (document_id) REFERENCES kb_document (id) ON DELETE SET NULL,
    CONSTRAINT fk_kb_attempt_version
        FOREIGN KEY (version_id) REFERENCES kb_document_version (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE kb_document_version
    ADD COLUMN embedding_model VARCHAR(255) NULL,
    ADD COLUMN embedding_dimension INT NULL,
    ADD COLUMN embedding_source VARCHAR(24) NULL,
    ADD COLUMN embedded_at TIMESTAMP(6) NULL,
    ADD COLUMN embedding_content_checksum CHAR(64) NULL;

-- Existing bundled demo versions were produced by the deterministic seed path before
-- provenance columns existed. Backfill that known space; all subsequent uploads record
-- their actual hosted/deterministic batch at ingestion time.
UPDATE kb_document_version
   SET embedding_model = 'deterministic-hashed-bow-v1',
       embedding_dimension = 1536,
       embedding_source = 'DETERMINISTIC',
       embedded_at = COALESCE(activated_at, created_at),
       embedding_content_checksum = checksum_sha256
 WHERE embedding_model IS NULL;

CREATE INDEX idx_kbv_embedding_space
    ON kb_document_version (status, embedding_source, embedding_dimension);
