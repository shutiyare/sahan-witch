-- =====================================================================================
-- V7: payment audit trail (Task 5.1)
--
-- Every status change of a payment writes exactly one row here, in the SAME database
-- transaction as the change itself. Rows are append-only: the trigger at the bottom
-- makes UPDATE and DELETE impossible, even for someone with direct database access
-- (short of dropping the trigger, which would itself be a visible schema change).
-- =====================================================================================

CREATE TABLE IF NOT EXISTS payment_audit_logs (
    id              UUID PRIMARY KEY,

    -- The payment this entry belongs to.
    payment_id      UUID        NOT NULL,

    -- NULL for the very first entry of a payment (it had no previous status yet).
    previous_status VARCHAR(30),

    new_status      VARCHAR(30) NOT NULL,

    -- Why the transition happened, e.g. the participant's rejection message.
    reason          TEXT,

    -- Ties the entry to the HTTP request (X-Correlation-Id) that caused it, so one request
    -- can be followed through logs and audit rows.
    correlation_id  VARCHAR(100),

    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_audit_logs_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id)
);

-- "Show me the history of payment X" is the main query.
CREATE INDEX IF NOT EXISTS idx_payment_audit_logs_payment_id
    ON payment_audit_logs (payment_id, created_at);

-- "Show me everything request Y did".
CREATE INDEX IF NOT EXISTS idx_payment_audit_logs_correlation_id
    ON payment_audit_logs (correlation_id);

-- ---- immutability ----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_payment_audit_log_modification()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment_audit_logs is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payment_audit_logs_immutable ON payment_audit_logs;

CREATE TRIGGER trg_payment_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON payment_audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_payment_audit_log_modification();
