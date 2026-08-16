CREATE TABLE IF NOT EXISTS payments (
                          id UUID PRIMARY KEY,

                          payment_reference VARCHAR(50) NOT NULL,

                          idempotency_key VARCHAR(100) NOT NULL,

                          participant_id UUID NOT NULL,

                          source_account VARCHAR(100) NOT NULL,

                          destination_account VARCHAR(100) NOT NULL,

                          amount NUMERIC(19, 4) NOT NULL,

                          currency VARCHAR(3) NOT NULL,

                          status VARCHAR(30) NOT NULL,

                          created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT uk_payments_reference UNIQUE (payment_reference),

                          CONSTRAINT uk_payments_participant_idempotency UNIQUE (participant_id, idempotency_key),

                          CONSTRAINT fk_payments_participant FOREIGN KEY (participant_id) REFERENCES participants(id),

                          CONSTRAINT chk_payments_amount_positive CHECK (amount > 0)
);