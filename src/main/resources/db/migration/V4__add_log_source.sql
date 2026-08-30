-- Where a service's logs can be read.
--
-- Stored as kind + location rather than a bare string, because the two kinds have different
-- guarantees and the investigator needs to know which it has: a FILE path outlives the process
-- that wrote it, while the ACTUATOR endpoint is served by that process and dies with it. The
-- second is useless for exactly the incident you most want logs for.
--
-- A FILE path is read from pop's own host, so it is a local-file-inclusion surface and is
-- vetted against pop.security.allowed-log-dirs before anything is opened.

ALTER TABLE monitored_services
    ADD COLUMN log_source_kind     VARCHAR(16),
    ADD COLUMN log_source_location VARCHAR(512);

ALTER TABLE monitored_services
    ADD CONSTRAINT ck_monitored_services_log_source_complete CHECK (
        (log_source_kind IS NULL AND log_source_location IS NULL)
        OR (log_source_kind IS NOT NULL AND log_source_location IS NOT NULL)
    );
