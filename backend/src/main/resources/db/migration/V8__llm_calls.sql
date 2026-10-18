-- AI observability: one row per LLM attempt. Created last because questions, evaluations and reports reference it.
-- Rows are kept for aggregate metrics when a user or interview is deleted (SET NULL).

CREATE TABLE llm_calls (
    id                  uuid          NOT NULL,
    interview_id        uuid,
    user_id             uuid,
    trace_id            varchar(64),
    task                varchar(40)   NOT NULL,
    provider            varchar(20)   NOT NULL,
    model               varchar(80)   NOT NULL,
    model_version       varchar(80),
    prompt_id           varchar(60),
    prompt_version      varchar(20),
    attempt             smallint      NOT NULL DEFAULT 1,
    status              varchar(20)   NOT NULL,
    finish_reason       varchar(20),
    input_tokens        int,
    output_tokens       int,
    thinking_tokens     int,
    latency_ms          int           NOT NULL,
    estimated_cost_usd  numeric(10, 6),
    rag_chunk_ids       uuid[],
    error_code          varchar(60),
    error_message       varchar(500),
    request_payload     jsonb,
    response_payload    jsonb,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_llm_calls PRIMARY KEY (id),
    CONSTRAINT fk_llm_calls_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_calls_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_llm_calls_status CHECK (status IN ('OK', 'REPAIRED', 'INVALID_OUTPUT', 'RATE_LIMITED', 'TIMEOUT', 'BLOCKED', 'ERROR', 'FALLBACK')),
    CONSTRAINT ck_llm_calls_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_llm_calls_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_llm_calls_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0 AND thinking_tokens >= 0)
);
CREATE INDEX ix_llm_calls_interview ON llm_calls (interview_id, created_at);
CREATE INDEX ix_llm_calls_user_day ON llm_calls (user_id, created_at);
CREATE INDEX ix_llm_calls_task_time ON llm_calls (task, created_at DESC);
CREATE INDEX ix_llm_calls_created ON llm_calls (created_at DESC);

ALTER TABLE evaluations ADD CONSTRAINT fk_evaluations_llm_call
    FOREIGN KEY (llm_call_id) REFERENCES llm_calls (id) ON DELETE SET NULL;
ALTER TABLE questions ADD CONSTRAINT fk_questions_generation_llm_call
    FOREIGN KEY (generation_llm_call_id) REFERENCES llm_calls (id) ON DELETE SET NULL;
ALTER TABLE reports ADD CONSTRAINT fk_reports_llm_call
    FOREIGN KEY (llm_call_id) REFERENCES llm_calls (id) ON DELETE SET NULL;

COMMENT ON TABLE llm_calls IS 'One row per LLM attempt. request_payload/response_payload are opt-in and nulled after 30 days; rows themselves are retained for aggregate metrics.';
COMMENT ON COLUMN llm_calls.rag_chunk_ids IS 'Chunks supplied as context. Deliberately not a foreign key so the log survives chunk deletion.';
