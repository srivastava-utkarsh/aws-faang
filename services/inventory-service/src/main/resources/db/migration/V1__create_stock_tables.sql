-- Runs in the `inventory` schema (see application.yaml: flyway.default-schema).
-- order-service owns `public`; this service owns `inventory`. Separate schemas
-- mean separate Flyway history tables, so the two services migrate the same
-- Aurora cluster independently without fighting over flyway_schema_history.

CREATE TABLE stock (
    product_id      VARCHAR(64) PRIMARY KEY,
    available_qty   INTEGER NOT NULL CHECK (available_qty >= 0),
    reserved_qty    INTEGER NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    -- Optimistic locking: Hibernate bumps this on every UPDATE and adds
    -- "WHERE version = ?" to the statement. Two concurrent reservations for
    -- the same product mean the slower one updates 0 rows and Hibernate
    -- raises an optimistic-lock failure instead of silently overselling.
    version         BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency guard for reservations. The order-processor Lambda can be
-- redelivered the same SQS message (at-least-once delivery), and without
-- this a retry would reserve the same stock twice.
CREATE TABLE stock_reservations (
    order_id        UUID PRIMARY KEY,
    reserved_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed data so the flow is testable immediately after deploy. In a real
-- system stock would arrive from a supplier/catalogue feed, not a migration.
INSERT INTO stock (product_id, available_qty) VALUES
    ('prod-001', 100),
    ('prod-002', 50),
    ('prod-003', 25),
    ('p1', 100),
    ('p2', 100);
