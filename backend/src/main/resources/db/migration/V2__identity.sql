-- Identity: accounts and rotating refresh tokens.

CREATE TABLE users (
    id             uuid         NOT NULL,
    email          citext       NOT NULL,
    password_hash  varchar(100) NOT NULL,
    display_name   varchar(80)  NOT NULL,
    role           varchar(20)  NOT NULL,
    enabled        boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('CANDIDATE', 'ADMIN'))
);

CREATE TABLE refresh_tokens (
    id          uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    family_id   uuid        NOT NULL,
    token_hash  char(64)    NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id) ON DELETE SET NULL
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_replaced_by ON refresh_tokens (replaced_by) WHERE replaced_by IS NOT NULL;

COMMENT ON COLUMN users.email IS 'citext: unique case-insensitively.';
COMMENT ON TABLE refresh_tokens IS 'Rotating refresh tokens. family_id links a rotation chain; reuse of a revoked token revokes the whole family. Only the SHA-256 hex of the raw token is stored.';
