-- Platform-owned schema: the history of investigations pop has run.
-- This is NOT the database being diagnosed; that one is reached read-only via a
-- separate DataSource and is never migrated by pop.

CREATE TABLE investigations (
    id              UUID         PRIMARY KEY,
    question        TEXT         NOT NULL,
    service_name    VARCHAR(128) NOT NULL,
    range_from      TIMESTAMPTZ  NOT NULL,
    range_to        TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    root_cause      TEXT,
    confidence      VARCHAR(16),
    summary         TEXT,
    failure_reason  TEXT,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_investigations_range CHECK (range_from < range_to)
);

CREATE INDEX idx_investigations_created_at ON investigations (created_at DESC, id DESC);
CREATE INDEX idx_investigations_service_status ON investigations (service_name, status);

-- Ordered remediation steps of the diagnosis.
CREATE TABLE investigation_remediation_steps (
    investigation_id UUID    NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    step_order       INTEGER NOT NULL,
    step             TEXT    NOT NULL,
    PRIMARY KEY (investigation_id, step_order)
);

-- Titles of the findings the diagnosis rests on.
CREATE TABLE investigation_supporting_findings (
    investigation_id UUID    NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    finding_order    INTEGER NOT NULL,
    finding_title    TEXT    NOT NULL,
    PRIMARY KEY (investigation_id, finding_order)
);

CREATE TABLE findings (
    id               UUID         PRIMARY KEY,
    investigation_id UUID         NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    source           VARCHAR(32)  NOT NULL,
    severity         VARCHAR(16)  NOT NULL,
    title            TEXT         NOT NULL,
    detail           TEXT         NOT NULL DEFAULT '',
    observed_at      TIMESTAMPTZ  NOT NULL,
    finding_order    INTEGER      NOT NULL
);

CREATE INDEX idx_findings_investigation ON findings (investigation_id, finding_order);

-- Free-form supporting data per finding (query text, metric values, row counts).
CREATE TABLE finding_evidence (
    finding_id     UUID         NOT NULL REFERENCES findings (id) ON DELETE CASCADE,
    evidence_key   VARCHAR(128) NOT NULL,
    evidence_value TEXT         NOT NULL,
    PRIMARY KEY (finding_id, evidence_key)
);
