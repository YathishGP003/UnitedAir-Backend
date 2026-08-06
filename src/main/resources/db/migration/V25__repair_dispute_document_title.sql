-- Early ingestion builds could store the underline under the running header as
-- KB-AIR-008's catalog title. Keep existing databases truthful before the next
-- version is uploaded; IngestionService now also refreshes the title on upsert.
UPDATE kb_document
SET title = 'Dispute Resolution and Service Support'
WHERE document_code = 'KB-AIR-008'
  AND (
      title REGEXP '^[=[:space:]]+$'
      OR title = 'UnitedAir AI | KB 08 Dispute Resolution and Service Support'
  );
