ALTER TABLE answer_record
    ADD COLUMN commerce_json JSON NULL AFTER degraded_reason;
