-- Receiver (destination) participant. Nullable so payments created before this
-- migration (which only knew the sender) remain valid. New payments always set it.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS destination_participant_id UUID REFERENCES participants (id);

-- external_reference (VARCHAR(100)) and failure_reason (VARCHAR(500)) were already
-- created by V5. These statements are intentional no-ops kept to document the
-- columns this step relies on.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS external_reference VARCHAR(100),
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500);

-- payment_reference is already indexed by uk_payments_reference and participant_id
-- is already the leading column of uk_payments_participant_idempotency, so extra
-- indexes on them would only slow down writes. The destination FK is not indexed yet.
CREATE INDEX IF NOT EXISTS idx_payments_destination_participant_id
    ON payments (destination_participant_id);
