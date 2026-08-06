-- Mixed Passenger/Staff documents contain explicitly internal sections.
-- Narrow those chunks so authenticated passenger retrieval cannot select them.
-- Fresh uploads apply the same rule in ChunkAudienceResolver.

UPDATE kb_chunk
SET audience = 'Airline Staff'
WHERE document_code = 'KB-AIR-005'
  AND FIND_IN_SET('Passenger', audience) > 0
  AND FIND_IN_SET('Airline Staff', audience) > 0
  AND (
      LOWER(section) LIKE '%(airline staff)%'
      OR LOWER(section) LIKE '%revenue band%'
      OR LOWER(section) LIKE '%yield management%'
      OR LOWER(section) LIKE '%availability override%'
      OR LOWER(section) LIKE '%seat blocking%'
      OR LOWER(section) LIKE '%upgrade inventory management%'
      OR LOWER(section) LIKE '%governance & workflow%'
      OR LOWER(section) LIKE '%non-compliance%'
      OR LOWER(section) LIKE '%objectives%'
  );
