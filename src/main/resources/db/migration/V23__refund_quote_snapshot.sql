ALTER TABLE refund_work_item
    ADD COLUMN timing_band VARCHAR(160) NULL,
    ADD COLUMN policy_document_code VARCHAR(40) NULL,
    ADD COLUMN policy_section VARCHAR(180) NULL,
    ADD COLUMN quote_calculated_at TIMESTAMP(6) NULL;
