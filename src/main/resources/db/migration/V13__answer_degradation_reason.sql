ALTER TABLE answer_record
    ADD COLUMN degraded_reason VARCHAR(32) NULL AFTER ai_mode;
