-- The service registry: what pop is allowed to investigate, and where each service's
-- database lives. Before this table, the observed database came from static configuration
-- and one running instance could look at exactly one database.
--
-- db_password is AES-GCM ciphertext (base64 of iv || ciphertext || tag), never plaintext.
-- The key lives in pop.security.secret-key, outside this database.

CREATE TABLE monitored_services (
    name              VARCHAR(128) PRIMARY KEY,
    prometheus_label  VARCHAR(128),
    db_url            VARCHAR(512),
    db_username       VARCHAR(128),
    db_password       TEXT,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    registered_at     TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,

    -- A database target is all-or-nothing: a URL without credentials cannot be connected,
    -- and credentials without a URL have nothing to connect to.
    CONSTRAINT ck_monitored_services_db_complete CHECK (
        (db_url IS NULL AND db_username IS NULL AND db_password IS NULL)
        OR (db_url IS NOT NULL AND db_username IS NOT NULL AND db_password IS NOT NULL)
    )
);

CREATE INDEX idx_monitored_services_enabled ON monitored_services (enabled);
