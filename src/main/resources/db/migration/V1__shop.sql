-- ─── Shop domain (the "existing application" the MCP tools sit on top of) ──────────────────
CREATE TABLE shops (
    id       uuid PRIMARY KEY,
    name     varchar(200) NOT NULL,
    currency varchar(3)   NOT NULL DEFAULT 'VND',
    timezone varchar(64)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    status   varchar(16)  NOT NULL DEFAULT 'active'
);

-- Role per shop. OWNER has every permission; STAFF has the keys listed in permissions.
CREATE TABLE shop_members (
    user_id     uuid        NOT NULL,
    shop_id     uuid        NOT NULL REFERENCES shops (id),
    display_name varchar(120) NOT NULL,
    role        varchar(16) NOT NULL,           -- OWNER | STAFF
    permissions text,                           -- comma separated, e.g. catalog_read,orders_read
    status      varchar(16) NOT NULL DEFAULT 'active',
    PRIMARY KEY (user_id, shop_id)
);

CREATE TABLE products (
    id       uuid PRIMARY KEY,
    shop_id  uuid          NOT NULL REFERENCES shops (id),
    sku      varchar(40)   NOT NULL,
    name     varchar(200)  NOT NULL,
    category varchar(80),
    price    numeric(14,2) NOT NULL,
    active   boolean       NOT NULL DEFAULT true,
    CONSTRAINT uq_products_sku UNIQUE (shop_id, sku)
);
CREATE INDEX idx_products_name ON products (shop_id, lower(name));

CREATE TABLE stock (
    shop_id       uuid NOT NULL,
    product_id    uuid NOT NULL REFERENCES products (id),
    on_hand       int  NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reorder_level int  NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id, product_id)
);

CREATE TABLE stock_movements (
    id         uuid PRIMARY KEY,
    shop_id    uuid         NOT NULL,
    product_id uuid         NOT NULL,
    delta      int          NOT NULL,
    reason     varchar(200) NOT NULL,
    ref_code   varchar(32),
    created_by uuid,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE customers (
    id         uuid PRIMARY KEY,
    shop_id    uuid         NOT NULL REFERENCES shops (id),
    name       varchar(200) NOT NULL,
    phone      varchar(20),
    email      varchar(200),
    note       text,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_phone UNIQUE (shop_id, phone)
);

CREATE SEQUENCE order_code_seq START 1001;

CREATE TABLE orders (
    id            uuid PRIMARY KEY,
    shop_id       uuid          NOT NULL REFERENCES shops (id),
    code          varchar(16)   NOT NULL,
    customer_id   uuid REFERENCES customers (id),
    status        varchar(16)   NOT NULL,       -- pending | paid | shipped | cancelled
    total         numeric(14,2) NOT NULL,
    note          text,
    cancel_reason text,
    created_by    uuid,
    created_via   varchar(16)   NOT NULL DEFAULT 'app',
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_code UNIQUE (shop_id, code)
);
CREATE INDEX idx_orders_shop_created ON orders (shop_id, created_at);

CREATE TABLE order_items (
    order_id   uuid          NOT NULL REFERENCES orders (id),
    product_id uuid          NOT NULL REFERENCES products (id),
    qty        int           NOT NULL CHECK (qty > 0),
    unit_price numeric(14,2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
