-- ─── MCP connector ─────────────────────────────────────────────────────────────────────────
-- One row per consent: which user, which shop, which AI client, what it may do.
CREATE TABLE mcp_grants (
    id              uuid PRIMARY KEY,
    user_id         uuid         NOT NULL,
    shop_id         uuid         NOT NULL REFERENCES shops (id),
    client_id       varchar(512) NOT NULL,
    scope           varchar(128) NOT NULL DEFAULT 'shop.read',   -- + 'shop.write' when ticked
    areas           varchar(200) NOT NULL DEFAULT 'catalog',     -- areas the user opened to the AI
    suspended_until timestamptz,
    suspend_reason  text,
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

-- O(1) daily quota: one counter row per shop per day, incremented atomically.
CREATE TABLE mcp_usage_daily (
    shop_id uuid NOT NULL,
    day     date NOT NULL,
    calls   int  NOT NULL DEFAULT 0,
    writes  int  NOT NULL DEFAULT 0,
    PRIMARY KEY (shop_id, day)
);

CREATE TABLE mcp_tool_calls (
    id          uuid PRIMARY KEY,
    grant_id    uuid         NOT NULL,
    user_id     uuid         NOT NULL,
    shop_id     uuid         NOT NULL,
    client_id   varchar(512),
    tool        varchar(64)  NOT NULL,
    args        jsonb,
    status      varchar(16)  NOT NULL,   -- ok | preview | replayed | denied | limited | invalid | timeout | error
    duration_ms int          NOT NULL,
    row_count   int,
    error       text,
    ref_type    varchar(32),
    ref_code    varchar(64),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcp_tool_calls_grant ON mcp_tool_calls (grant_id, created_at DESC);
CREATE INDEX idx_mcp_tool_calls_shop ON mcp_tool_calls (shop_id, created_at DESC);

CREATE TABLE mcp_write_requests (
    grant_id   uuid        NOT NULL,
    idem_key   varchar(64) NOT NULL,
    tool       varchar(64) NOT NULL,
    status     varchar(16) NOT NULL,     -- pending | done | failed
    result     jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (grant_id, idem_key)
);

CREATE TABLE mcp_confirmations (
    token_hash varchar(64) PRIMARY KEY,
    grant_id   uuid        NOT NULL,
    tool       varchar(64) NOT NULL,
    args_hash  varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);
