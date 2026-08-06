-- ===========================================================================
--  V1 - Baseline
--  Establishes the migration history anchor. No objects are created here; the
--  marker table lets us assert that Flyway owns this schema before any DDL
--  runs, which makes an accidental point at a pre-existing database fail loudly
--  instead of silently mutating it.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS schema_marker (
    id           TINYINT      NOT NULL PRIMARY KEY,
    application  VARCHAR(64)  NOT NULL,
    srs_version  VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO schema_marker (id, application, srs_version)
VALUES (1, 'unitedair-ai', '1.0')
ON DUPLICATE KEY UPDATE application = VALUES(application);
