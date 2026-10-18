-- Coding interview module (AI_ARCHITECTURE.md section 2.11).

-- The curated problem a coding question was taken from (resources/coding-problems/<id>.json). Its tests grade the solution.
-- NULL for every other question, and for coding problems the AI wrote.
ALTER TABLE questions ADD COLUMN coding_problem_id varchar(60);
ALTER TABLE questions ADD CONSTRAINT ck_questions_coding_problem
    CHECK (coding_problem_id IS NULL OR (kind = 'CODING' AND source = 'STATIC'));

-- The run of a submitted solution against all tests of its problem, written once before the answer is evaluated. The scoring
-- rules read it (the tests decide correctness), the report shows it after the interview. The inputs and expected values are
-- not copied here: they are in the problem bank.
CREATE TABLE code_test_runs (
    id                uuid         NOT NULL,
    answer_id         uuid         NOT NULL,
    interview_id      uuid         NOT NULL,
    problem_id        varchar(60)  NOT NULL,
    language          varchar(20)  NOT NULL,
    status            varchar(20)  NOT NULL,
    passed            int          NOT NULL,
    total             int          NOT NULL,
    outcomes          jsonb        NOT NULL,
    compile_output    text,
    candidate_output  text         NOT NULL,
    duration_ms       bigint       NOT NULL,
    created_at        timestamptz  NOT NULL,
    CONSTRAINT pk_code_test_runs PRIMARY KEY (id),
    CONSTRAINT uq_code_test_runs_answer UNIQUE (answer_id),
    CONSTRAINT fk_code_test_runs_answer FOREIGN KEY (answer_id) REFERENCES answers (id) ON DELETE CASCADE,
    CONSTRAINT fk_code_test_runs_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE CASCADE,
    CONSTRAINT ck_code_test_runs_status CHECK (status IN ('COMPLETED', 'COMPILATION_ERROR', 'TIMED_OUT', 'CRASHED', 'OUTPUT_LIMIT')),
    CONSTRAINT ck_code_test_runs_counts CHECK (total >= 0 AND passed >= 0 AND passed <= total),
    CONSTRAINT ck_code_test_runs_duration CHECK (duration_ms >= 0)
);
CREATE INDEX ix_code_test_runs_interview ON code_test_runs (interview_id);

-- The evaluator's reading of a coding solution: its approach and its own time and space complexity. NULL for other answers
-- and for evaluations written before this migration.
ALTER TABLE evaluations ADD COLUMN code_analysis jsonb;

COMMENT ON TABLE code_test_runs IS 'Sandboxed test run of a submitted coding solution; the tests, not the model, decide its correctness.';
COMMENT ON COLUMN code_test_runs.outcomes IS 'Per test: testId, name, category, visible, status, timeMs, message, actual (shortened). No inputs or expected values.';
COMMENT ON COLUMN evaluations.code_analysis IS 'Approach, timeComplexity and spaceComplexity of a coding solution as the evaluator read them.';
