CREATE TABLE system_metadata (
                                 id UUID PRIMARY KEY,
                                 system_name VARCHAR(100) NOT NULL,
                                 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);