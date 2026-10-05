-- =====================================================================================
-- V10: transactional outbox (Task 2)
--
-- Problem: "save the payment" and "tell the message broker about it" are two systems. If the
-- application writes to the database and then publishes, a crash between the two loses the
-- message; if it publishes first, the broker can hold a message about a payment that was
-- never committed.
--
-- Solution: write the message into THIS table in the SAME transaction as the payment. A
-- background relay (OutboxProcessor) later reads PENDING rows and publishes them, marking
-- each PUBLISHED once the broker has confirmed it. Delivery is therefore at-least-once;
-- consumers must tolerate a repeated message (the routing consumer does).
-- =====================================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID PRIMARY KEY,

    -- What the event is about, e.g. PAYMENT + the payment's id.
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,

    -- PaymentRoutingRequested, PaymentStatusChanged, ...
    type           VARCHAR(50)  NOT NULL,

    -- The message body. JSONB so it can be inspected and queried while debugging.
    payload        JSONB        NOT NULL,

    -- PENDING -> PUBLISHED, or FAILED after too many failed publish attempts.
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Bookkeeping that makes retries safe and failures diagnosable.
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,

    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- The relay's query: "oldest PENDING rows first". Partial, so published history costs nothing.
CREATE INDEX IF NOT EXISTS idx_outbox_events_pending
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

-- "What was published about payment X?"
CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);
