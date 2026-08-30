-- Services can now be registered by their own URL, and read directly from Spring Boot
-- Actuator rather than only through Prometheus. Actuator is a point-in-time reading taken
-- from the process itself; Prometheus keeps the history.
--
-- No credentials here: the endpoint is fetched unauthenticated, the same way Prometheus
-- scrapes it. If that changes, the secret belongs in an encrypted column like db_password.

ALTER TABLE monitored_services
    ADD COLUMN actuator_base_url VARCHAR(512);

-- A registration has to be *some* kind of target, or nothing will ever investigate it.
-- Prometheus always applies (the label falls back to the service name), so this is advisory
-- rather than a hard constraint - it documents the intent without rejecting metrics-only rows.
COMMENT ON COLUMN monitored_services.actuator_base_url IS
    'Normalised actuator base, e.g. http://host:3001/actuator. Null means no direct probing.';
