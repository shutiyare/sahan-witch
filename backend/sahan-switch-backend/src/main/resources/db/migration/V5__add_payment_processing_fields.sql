ALTER TABLE payments
    ADD COLUMN external_reference VARCHAR(100),
    ADD COLUMN failure_reason VARCHAR(500);