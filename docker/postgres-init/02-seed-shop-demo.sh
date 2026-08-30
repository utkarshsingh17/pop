#!/bin/bash
# Seeds the `shop` database with a deliberately diagnosable problem:
# a large orders table with NO index on customer_id, so the lookup the demo
# workload runs degenerates into a sequential scan.
#
# This is the scenario the end-to-end demo asks pop to explain.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname shop <<-'EOSQL'
    CREATE TABLE customers (
        id         BIGSERIAL PRIMARY KEY,
        email      VARCHAR(255) NOT NULL UNIQUE,
        created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

    CREATE TABLE orders (
        id          BIGSERIAL PRIMARY KEY,
        customer_id BIGINT       NOT NULL,
        status      VARCHAR(32)  NOT NULL,
        total_cents BIGINT       NOT NULL,
        created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        -- Deliberately NO index on customer_id. This is the bug to be found.
    );

    INSERT INTO customers (email)
    SELECT 'customer' || i || '@example.com'
    FROM generate_series(1, 5000) AS i;

    INSERT INTO orders (customer_id, status, total_cents, created_at)
    SELECT (random() * 4999 + 1)::BIGINT,
           (ARRAY['PENDING','PAID','SHIPPED','DELIVERED'])[floor(random() * 4 + 1)],
           (random() * 50000)::BIGINT,
           NOW() - (random() * INTERVAL '30 days')
    FROM generate_series(1, 400000);

    ANALYZE customers;
    ANALYZE orders;

    GRANT SELECT ON ALL TABLES IN SCHEMA public TO pop_readonly;
EOSQL

# Generate the slow query a few times so pg_stat_statements has something to report.
for _ in $(seq 1 25); do
    psql -q --username "$POSTGRES_USER" --dbname shop \
        -c "SELECT count(*), sum(total_cents) FROM orders WHERE customer_id = (random() * 4999 + 1)::BIGINT;" \
        >/dev/null
done
