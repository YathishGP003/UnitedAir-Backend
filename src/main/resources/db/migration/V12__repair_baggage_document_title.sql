-- Repair metadata seeded by early builds that mistook the TXT underline for a title.
UPDATE kb_document
SET title = 'Baggage Policy and Handling'
WHERE document_code = 'KB-AIR-003'
  AND title REGEXP '^[=[:space:]]+$';
