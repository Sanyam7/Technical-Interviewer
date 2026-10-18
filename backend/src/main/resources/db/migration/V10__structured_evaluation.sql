-- Structured candidate evaluation (AI_ARCHITECTURE.md section 2.10).
-- The evaluator states how sure it is of each evaluation. It is the model's own estimate, not a calibrated probability: it never
-- changes a score, the server only lowers it when its own checks contradict the model, and LOW marks the answer for human review.
-- Evaluations written before this migration have none (NULL), as does the introduction, which is not technical evidence.
ALTER TABLE evaluations
    ADD COLUMN evaluator_confidence        varchar(10),
    ADD COLUMN evaluator_confidence_reason varchar(500);

ALTER TABLE evaluations ADD CONSTRAINT ck_evaluations_evaluator_confidence CHECK (evaluator_confidence IN ('LOW', 'MEDIUM', 'HIGH'));
ALTER TABLE evaluations ADD CONSTRAINT ck_evaluations_evaluator_confidence_reason
    CHECK ((evaluator_confidence IS NULL) = (evaluator_confidence_reason IS NULL));

-- Knowledge gaps of the interview, computed without a model from the evaluations: missed concepts, misconceptions, weak topics
-- and repeated mistakes. NULL for reports written before this migration and for reports that are not READY.
ALTER TABLE reports ADD COLUMN knowledge_gaps jsonb;

COMMENT ON COLUMN evaluations.evaluator_confidence IS 'How sure the evaluator said it was (LOW, MEDIUM, HIGH), after the server lowered it where its checks disagreed. Self-reported by the model and not statistically calibrated; never changes the score.';
COMMENT ON COLUMN evaluations.evaluator_confidence_reason IS 'The evaluator''s one-sentence reason for its confidence, with the server''s reason when it lowered it.';
COMMENT ON COLUMN reports.knowledge_gaps IS 'Deterministic knowledge-gap analysis of the interview: missedConcepts, misconceptions, weakTopics, repeatedMistakes.';
