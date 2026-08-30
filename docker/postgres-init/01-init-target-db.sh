#!/bin/bash
# Creates the database pop *diagnoses* (`shop`), separate from pop's own (`pop`),
# plus a read-only role. pop connects to `shop` with this role only — it can read
# statistics and run SELECTs, and nothing else.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE shop;

    CREATE ROLE pop_readonly WITH LOGIN PASSWORD 'pop_readonly';

    -- pg_stat_statements and pg_stat_activity expose other sessions' query text
    -- only to superusers or members of pg_read_all_stats.
    GRANT pg_read_all_stats TO pop_readonly;
EOSQL

# Extensions are per-database, so install into both.
for db in "$POSTGRES_DB" shop; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
        CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
EOSQL
done

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname shop <<-EOSQL
    GRANT CONNECT ON DATABASE shop TO pop_readonly;
    GRANT USAGE ON SCHEMA public TO pop_readonly;
    GRANT SELECT ON ALL TABLES IN SCHEMA public TO pop_readonly;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO pop_readonly;

    -- Belt and braces: even if a SELECT slips past SqlSafetyGuard, the role cannot write.
    REVOKE CREATE ON SCHEMA public FROM pop_readonly;
EOSQL
