-- The write model: normalised, constrained, and shaped for correctness rather than for
-- any particular screen.

CREATE TABLE customers (
    id    UUID         PRIMARY KEY,
    name  VARCHAR(160) NOT NULL,
    email VARCHAR(160) NOT NULL UNIQUE
);

CREATE TABLE products (
    id       UUID           PRIMARY KEY,
    sku      VARCHAR(40)    NOT NULL UNIQUE,
    name     VARCHAR(160)   NOT NULL,
    category VARCHAR(60)    NOT NULL,
    price    NUMERIC(12, 2) NOT NULL CHECK (price > 0)
);

CREATE TABLE orders (
    id          UUID           PRIMARY KEY,
    customer_id UUID           NOT NULL REFERENCES customers (id),
    status      VARCHAR(20)    NOT NULL,
    total       NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    version     BIGINT         NOT NULL DEFAULT 0,
    placed_at   TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL
);

CREATE TABLE order_items (
    id         UUID           PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id UUID           NOT NULL REFERENCES products (id),
    quantity   INT            NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price > 0)
);

CREATE INDEX idx_orders_customer ON orders (customer_id, placed_at DESC);
CREATE INDEX idx_order_items_order ON order_items (order_id);

-- The channel between the two models.
--
-- `sequence` is a BIGSERIAL and is the backbone of the whole arrangement: it gives the
-- projector a total order to consume in, a position to checkpoint at, and a number the
-- client can quote when it wants to read its own write. Timestamps cannot do any of that —
-- two events in the same millisecond have no order, and clocks move backwards.
CREATE TABLE domain_event (
    sequence     BIGSERIAL   PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    type         VARCHAR(60) NOT NULL,
    aggregate_id UUID        NOT NULL,
    payload      TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_domain_event_aggregate ON domain_event (aggregate_id, sequence);
