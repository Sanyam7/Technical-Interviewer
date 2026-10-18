-- Evaluations and reports. llm_call_id columns are plain here; V8 adds their foreign keys once llm_calls exists.

CREATE TABLE evaluations (
    id                uuid          NOT NULL,
    answer_id         uuid          NOT NULL,
    evaluator         varchar(20)   NOT NULL,
    overall_score     numeric(5, 2) NOT NULL,
    criterion_scores  jsonb         NOT NULL,
    key_points        jsonb         NOT NULL,
    factual_errors    jsonb         NOT NULL DEFAULT '[]'::jsonb,
    candidate_claims  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    summary           text          NOT NULL,
    flags             text[]        NOT NULL DEFAULT '{}',
    prompt_id         varchar(60)   NOT NULL,
    prompt_version    varchar(20)   NOT NULL,
    model             varchar(80)   NOT NULL,
    llm_call_id       uuid,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_evaluations PRIMARY KEY (id),
    CONSTRAINT uq_evaluations_answer UNIQUE (answer_id),
    CONSTRAINT fk_evaluations_answer FOREIGN KEY (answer_id) REFERENCES answers (id) ON DELETE CASCADE,
    CONSTRAINT ck_evaluations_evaluator CHECK (evaluator IN ('INTRO', 'TECHNICAL', 'CODING', 'SYSTEM_DESIGN')),
    CONSTRAINT ck_evaluations_overall_score CHECK (overall_score BETWEEN 0 AND 100)
);

CREATE TABLE reports (
    id               uuid          NOT NULL,
    interview_id     uuid          NOT NULL,
    status           varchar(20)   NOT NULL,
    overall_score    numeric(5, 2),
    recommendation   varchar(20),
    section_scores   jsonb,
    narrative        jsonb,
    integrity_notes  jsonb,
    coverage         jsonb,
    attempts         smallint      NOT NULL DEFAULT 0,
    error_message    varchar(500),
    prompt_version   varchar(20),
    llm_call_id      uuid,
    generated_at     timestamptz,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_reports PRIMARY KEY (id),
    CONSTRAINT uq_reports_interview UNIQUE (interview_id),
    CONSTRAINT fk_reports_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE CASCADE,
    CONSTRAINT ck_reports_status CHECK (status IN ('PENDING', 'GENERATING', 'READY', 'FAILED')),
    CONSTRAINT ck_reports_overall_score CHECK (overall_score BETWEEN 0 AND 100),
    CONSTRAINT ck_reports_recommendation CHECK (recommendation IN ('STRONG', 'PROFICIENT', 'DEVELOPING', 'NOT_YET')),
    CONSTRAINT ck_reports_attempts CHECK (attempts >= 0)
);

CREATE TABLE report_skill_scores (
    report_id       uuid          NOT NULL,
    skill_id        bigint        NOT NULL,
    score           numeric(5, 2) NOT NULL,
    confidence      varchar(10)   NOT NULL,
    evidence_count  smallint      NOT NULL,
    avg_difficulty  numeric(3, 2) NOT NULL,
    CONSTRAINT pk_report_skill_scores PRIMARY KEY (report_id, skill_id),
    CONSTRAINT fk_report_skill_scores_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_skill_scores_skill FOREIGN KEY (skill_id) REFERENCES skills (id),
    CONSTRAINT ck_report_skill_scores_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_report_skill_scores_confidence CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_report_skill_scores_evidence_count CHECK (evidence_count >= 0),
    CONSTRAINT ck_report_skill_scores_avg_difficulty CHECK (avg_difficulty BETWEEN 1 AND 5)
);
CREATE INDEX ix_report_skill_scores_skill ON report_skill_scores (skill_id);

COMMENT ON COLUMN evaluations.overall_score IS 'Computed in Java from criterion ratings; never taken from the model.';
COMMENT ON COLUMN evaluations.llm_call_id IS 'References llm_calls(id); the foreign key is added in V8.';
COMMENT ON COLUMN reports.llm_call_id IS 'References llm_calls(id); the foreign key is added in V8.';
COMMENT ON TABLE report_skill_scores IS 'Relational (not JSONB) so progress-over-time queries per skill stay simple SQL.';
