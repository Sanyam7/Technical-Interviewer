-- Evaluation give-up policy (AI_ARCHITECTURE.md section 10.6).
-- An answer whose evaluation keeps failing (provider failure, invalid output) is retried at most a fixed number of times; one the
-- provider refuses to judge (safety block) is not retried. Either way it ends UNASSESSABLE instead of keeping the interview in
-- FINAL_EVALUATION forever. UNASSESSABLE is not a score of zero: the report is computed from the assessable answers and says how much
-- of the intended assessment weight they cover (reports.outcome COMPLETE, PARTIAL or INCONCLUSIVE).

-- FAILED was never written (nothing marked an answer failed); an answer waiting for another attempt simply stays PENDING.
UPDATE answers SET evaluation_status = 'PENDING' WHERE evaluation_status = 'FAILED';

ALTER TABLE answers DROP CONSTRAINT ck_answers_evaluation_status;
ALTER TABLE answers ADD CONSTRAINT ck_answers_evaluation_status CHECK (evaluation_status IN ('PENDING', 'COMPLETED', 'UNASSESSABLE'));

ALTER TABLE answers
    ADD COLUMN evaluation_attempts smallint    NOT NULL DEFAULT 0,
    ADD COLUMN next_evaluation_at  timestamptz,
    ADD COLUMN evaluation_failure  varchar(20);

ALTER TABLE answers ADD CONSTRAINT ck_answers_evaluation_attempts CHECK (evaluation_attempts >= 0);
ALTER TABLE answers ADD CONSTRAINT ck_answers_evaluation_failure CHECK (evaluation_failure IN ('PROVIDER_FAILURE', 'INVALID_OUTPUT', 'SAFETY_BLOCK'));
ALTER TABLE answers ADD CONSTRAINT ck_answers_unassessable_has_failure CHECK (evaluation_status <> 'UNASSESSABLE' OR evaluation_failure IS NOT NULL);

-- The pending lookup only needs the answers that still wait for an attempt.
DROP INDEX ix_answers_pending;
CREATE INDEX ix_answers_pending ON answers (interview_id) WHERE evaluation_status = 'PENDING';

ALTER TABLE reports ADD COLUMN outcome varchar(14) NOT NULL DEFAULT 'COMPLETE';
ALTER TABLE reports ADD CONSTRAINT ck_reports_outcome CHECK (outcome IN ('COMPLETE', 'PARTIAL', 'INCONCLUSIVE'));
ALTER TABLE reports ADD CONSTRAINT ck_reports_inconclusive_has_no_verdict
    CHECK (outcome <> 'INCONCLUSIVE' OR (overall_score IS NULL AND recommendation IS NULL));

COMMENT ON COLUMN answers.evaluation_attempts IS 'Evaluation attempts started (claimed) for this answer; bounded by app.evaluation.max-attempts.';
COMMENT ON COLUMN answers.next_evaluation_at IS 'No attempt may start before this instant: the backoff after a failed attempt, or the lease of an attempt in progress (it survives a process restart).';
COMMENT ON COLUMN answers.evaluation_failure IS 'Why the last attempt failed; for an UNASSESSABLE answer, why it was given up.';
COMMENT ON COLUMN reports.outcome IS 'COMPLETE: every answer was assessed. PARTIAL: some were not, but they are a small part of the assessment weight. INCONCLUSIVE: too little was assessable; no overall score or recommendation.';
