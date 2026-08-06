-- KB-AIR-005's preface describes internal revenue-management scope and is not
-- passenger policy evidence. The actual passenger seat and upgrade sections remain shared.

UPDATE kb_chunk
SET audience = 'Airline Staff'
WHERE document_code = 'KB-AIR-005'
  AND FIND_IN_SET('Passenger', audience) > 0
  AND FIND_IN_SET('Airline Staff', audience) > 0
  AND (
      LOWER(section) = 'introduction'
      OR LOWER(section) LIKE '%purpose & scope%'
  );
