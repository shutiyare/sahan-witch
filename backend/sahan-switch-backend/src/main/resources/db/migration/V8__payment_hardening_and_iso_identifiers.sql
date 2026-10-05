-- =====================================================================================
-- V8: payment hardening + ISO 20022 identifiers
--
-- Task 2.1 (remaining items of the spec)
--   * failure_reason becomes TEXT so a participant's full rejection message always fits.
--   * Performance indexes on payment_reference, participant_id and destination_participant_id.
--
-- Task 4 (ISO 20022 support)
--   * end_to_end_id, uetr, debtor_name and creditor_name, so a stored payment can be turned
--     back into a pacs.008 / pacs.002 message without losing the identifiers that the
--     original sender used.
--
-- Why a new migration instead of editing V6: V6 may already be applied to developer
-- databases. Flyway migrations are append-only, so changes are always a new version.
-- =====================================================================================

-- ---- Task 2.1: failure_reason -> TEXT (V5 created it as VARCHAR(500)) -------------------
ALTER TABLE payments
    ALTER COLUMN failure_reason TYPE TEXT;

-- ---- Task 2.1: indexes -----------------------------------------------------------------
-- NOTE: payment_reference already has the unique index uk_payments_reference, and
-- participant_id is the leading column of uk_payments_participant_idempotency, so
-- PostgreSQL can already serve most lookups through those. The two explicit indexes below
-- are requested by the spec; the price is a little extra write cost on every insert.
-- IF NOT EXISTS keeps the migration safe on databases that already created them by hand.
CREATE INDEX IF NOT EXISTS idx_payments_reference
    ON payments (payment_reference);

CREATE INDEX IF NOT EXISTS idx_payments_participant_id
    ON payments (participant_id);

-- Created in V6 already; repeated here so V8 alone guarantees all three indexes exist.
CREATE INDEX IF NOT EXISTS idx_payments_destination_participant_id
    ON payments (destination_participant_id);

-- ---- Task 4: ISO 20022 identifiers (all nullable: legacy rows and plain JSON payments) --
-- EndToEndId is Max35Text in ISO 20022; names are Max140Text.
-- UETR is a UUID v4 (the SWIFT gpi "unique end-to-end transaction reference").
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS end_to_end_id VARCHAR(35),
    ADD COLUMN IF NOT EXISTS uetr UUID,
    ADD COLUMN IF NOT EXISTS debtor_name VARCHAR(140),
    ADD COLUMN IF NOT EXISTS creditor_name VARCHAR(140);

-- A UETR identifies exactly one transaction on the network, so it must be unique.
-- Partial index: rows without a UETR (legacy data) are not constrained.
CREATE UNIQUE INDEX IF NOT EXISTS uk_payments_uetr
    ON payments (uetr)
    WHERE uetr IS NOT NULL;
