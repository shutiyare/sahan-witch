CREATE TABLE IF NOT EXISTS participants  (
                              id UUID PRIMARY KEY,

                              code VARCHAR(50) NOT NULL,
                              name VARCHAR(150) NOT NULL,

                              type VARCHAR(50) NOT NULL,
                              status VARCHAR(50) NOT NULL,

                              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT uk_participants_code UNIQUE (code)
);