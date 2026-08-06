-- ===========================================================================
--  V3 - Knowledge Base
--
--  Documents are versioned; exactly one version per document may be ACTIVE at
--  a time, and only ACTIVE versions are retrievable. Superseding a version is
--  therefore an atomic status flip, not a delete, which keeps the audit trail
--  intact (FR-032).
--
--  kb_chunk carries BOTH retrieval lanes over identical rows:
--    * embedding VECTOR(1536) + VECTOR INDEX  -> semantic lane
--    * FULLTEXT INDEX on content              -> lexical lane
--  Keeping one canonical table means the two lanes can never drift apart, and
--  the metadata filters of SRS 4.3.2 are ordinary SQL predicates evaluated
--  BEFORE similarity scoring rather than a post-filter over ranked results.
-- ===========================================================================

CREATE TABLE kb_document (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_code  VARCHAR(64)  NOT NULL,
    title          VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_kb_document_code (document_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kb_document_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT       NOT NULL,
    version_label   VARCHAR(32)  NOT NULL,
    -- DRAFT | ACTIVE | SUPERSEDED | FAILED
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    -- PDF | DOCX | TXT   (SRS 4.3.2 File Type filter)
    file_type       VARCHAR(8)   NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT       NOT NULL DEFAULT 0,
    checksum_sha256 CHAR(64)     NULL,
    -- SRS 4.3.2 Document Category: policy-manual | sop | fare-rule | regulatory-circular
    category        VARCHAR(64)  NOT NULL DEFAULT 'policy-manual',
    -- Comma-separated subset of: Passenger, Airline Staff, Admin, All
    audience        VARCHAR(128) NOT NULL DEFAULT 'All',
    effective_from  DATE         NULL,
    effective_to    DATE         NULL,
    approved_by     VARCHAR(160) NULL,
    classification  VARCHAR(160) NULL,
    -- Free-form key=value pairs lifted from the document front matter
    ingestion_tags  JSON         NULL,
    -- FR IDs this document serves, as declared in its Requirements Coverage table
    serves_frs      VARCHAR(512) NULL,
    uploaded_by     BIGINT       NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at    TIMESTAMP    NULL,
    KEY idx_kbv_document (document_id),
    KEY idx_kbv_status (status),
    KEY idx_kbv_category (category),
    UNIQUE KEY uq_kbv_doc_version (document_id, version_label),
    CONSTRAINT fk_kbv_document FOREIGN KEY (document_id) REFERENCES kb_document (id) ON DELETE CASCADE,
    CONSTRAINT fk_kbv_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- FR-032: every ingestion attempt is recorded with admin, counts and timings
CREATE TABLE kb_ingestion_job (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_uuid          CHAR(36)     NOT NULL,
    version_id        BIGINT       NOT NULL,
    -- QUEUED | RUNNING | SUCCEEDED | FAILED
    status            VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    triggered_by      BIGINT       NULL,
    failure_reason    VARCHAR(1000) NULL,
    chunks_created    INT          NOT NULL DEFAULT 0,
    tokens_embedded   INT          NOT NULL DEFAULT 0,
    ingestion_time_ms BIGINT       NULL,
    started_at        TIMESTAMP    NULL,
    finished_at       TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_kb_job_uuid (job_uuid),
    KEY idx_kb_job_version (version_id),
    KEY idx_kb_job_status (status),
    CONSTRAINT fk_kb_job_version FOREIGN KEY (version_id) REFERENCES kb_document_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_job_user FOREIGN KEY (triggered_by) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kb_chunk (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chunk_uuid     CHAR(36)      NOT NULL,
    version_id     BIGINT        NOT NULL,
    document_code  VARCHAR(64)   NOT NULL,
    document_title VARCHAR(255)  NOT NULL,
    chunk_index    INT           NOT NULL,

    -- Citation coordinates required by SRS 4.1.2
    section        VARCHAR(255)  NOT NULL DEFAULT '',
    section_path   VARCHAR(255)  NOT NULL DEFAULT '',
    page           INT           NOT NULL DEFAULT 1,

    -- Filter columns duplicated from the version row so retrieval needs no join
    file_type      VARCHAR(8)    NOT NULL,
    category       VARCHAR(64)   NOT NULL,
    audience       VARCHAR(128)  NOT NULL,
    effective_from DATE          NULL,
    effective_to   DATE          NULL,
    tags           JSON          NULL,

    content        MEDIUMTEXT    NOT NULL,
    token_estimate INT           NOT NULL DEFAULT 0,
    embedding      VECTOR(1536)  NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_kb_chunk_uuid (chunk_uuid),
    KEY idx_kb_chunk_version (version_id),
    KEY idx_kb_chunk_doc (document_code),
    KEY idx_kb_chunk_category (category),
    KEY idx_kb_chunk_filetype (file_type),
    FULLTEXT INDEX ftx_kb_chunk_content (content),
    VECTOR INDEX vec_kb_chunk_embedding (embedding) DISTANCE=cosine,
    CONSTRAINT fk_kb_chunk_version FOREIGN KEY (version_id) REFERENCES kb_document_version (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
