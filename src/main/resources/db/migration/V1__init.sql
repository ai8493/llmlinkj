CREATE TABLE backend_config (
    name                    TEXT PRIMARY KEY,
    protocol                TEXT NOT NULL,
    api_key                 TEXT NOT NULL,
    base_url                TEXT NOT NULL,
    default_model           TEXT NOT NULL,
    default_max_tokens      INTEGER,
    connect_timeout         TEXT NOT NULL,
    read_timeout            TEXT NOT NULL,
    write_timeout           TEXT NOT NULL,
    max_idle_connections    INTEGER NOT NULL DEFAULT 20,
    keep_alive_duration     TEXT NOT NULL DEFAULT '5m',
    updated_at              TEXT NOT NULL
);

CREATE TABLE protocol_mapping (
    inbound_protocol    TEXT NOT NULL,
    target_protocol     TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    is_default          INTEGER NOT NULL DEFAULT 0,
    backend_name        TEXT,
    updated_at          TEXT NOT NULL,
    PRIMARY KEY (inbound_protocol, target_protocol)
);

CREATE TABLE model_mapping (
    request_model       TEXT PRIMARY KEY,
    actual_model        TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);
