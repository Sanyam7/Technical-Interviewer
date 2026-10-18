-- Fundamentals-first interviewing: strategies, stages and question types (AI_ARCHITECTURE.md section 2.15).

-- How the questioning section divides its main questions between the stages (fundamentals, practical, project). A preset strategy
-- stores its own shares, CUSTOM the candidate's. Configurations created before strategies existed get the default,
-- FUNDAMENTALS_FIRST; the interviews already created from them keep the plan they were given.
ALTER TABLE interview_configurations ADD COLUMN strategy varchar(20) NOT NULL DEFAULT 'FUNDAMENTALS_FIRST';
ALTER TABLE interview_configurations ADD COLUMN fundamentals_share smallint NOT NULL DEFAULT 60;
ALTER TABLE interview_configurations ADD COLUMN practical_share smallint NOT NULL DEFAULT 25;
ALTER TABLE interview_configurations ADD COLUMN project_share smallint NOT NULL DEFAULT 15;
ALTER TABLE interview_configurations ADD CONSTRAINT ck_interview_configurations_strategy
    CHECK (strategy IN ('FUNDAMENTALS_FIRST', 'BALANCED', 'RESUME_FOCUSED', 'CUSTOM'));
ALTER TABLE interview_configurations ADD CONSTRAINT ck_interview_configurations_stage_shares
    CHECK (fundamentals_share BETWEEN 0 AND 100 AND practical_share BETWEEN 0 AND 100 AND project_share BETWEEN 0 AND 100
           AND fundamentals_share + practical_share + project_share = 100);
ALTER TABLE interview_configurations ADD CONSTRAINT ck_interview_configurations_resume_strategy
    CHECK (strategy <> 'RESUME_FOCUSED' OR use_resume);

-- What kind of questioning question was asked, decided by the engine: CONCEPT and INTERNALS (fundamentals), SCENARIO and DEBUGGING
-- (practical), EXPERIENCE (project). A follow-up carries the type of its thread, a claim probe EXPERIENCE. NULL outside the
-- questioning section and for questions asked before types existed.
ALTER TABLE questions ADD COLUMN question_type varchar(12);
ALTER TABLE questions ADD CONSTRAINT ck_questions_question_type
    CHECK (question_type IN ('CONCEPT', 'INTERNALS', 'SCENARIO', 'DEBUGGING', 'EXPERIENCE'));
ALTER TABLE questions ADD CONSTRAINT ck_questions_question_type_section
    CHECK (question_type IS NULL OR section = 'QUESTIONING');
ALTER TABLE questions ADD CONSTRAINT ck_questions_resume_item_type
    CHECK (resume_item_id IS NULL OR question_type IS NULL OR question_type = 'EXPERIENCE');

-- The curated template of the question bank (resources/question-bank/<skill>.json) a technical main question was written from.
ALTER TABLE questions ADD COLUMN template_id varchar(60);
ALTER TABLE questions ADD CONSTRAINT ck_questions_template
    CHECK (template_id IS NULL OR (kind = 'TECHNICAL' AND question_type IS NOT NULL));

-- The report's breakdown by assessment area (fundamentals, practical application, problem solving, project depth, communication),
-- what was not assessed, and the reason a recommendation was capped. NULL for reports made before it existed.
ALTER TABLE reports ADD COLUMN assessment_areas jsonb;

COMMENT ON COLUMN interview_configurations.strategy IS 'How the questioning section divides its questions between fundamentals, practical and project stages.';
COMMENT ON COLUMN questions.question_type IS 'CONCEPT, INTERNALS, SCENARIO, DEBUGGING or EXPERIENCE, decided by the engine; NULL outside questioning and before types existed.';
COMMENT ON COLUMN questions.template_id IS 'Question bank template the question was written from; NULL otherwise.';
COMMENT ON COLUMN reports.assessment_areas IS 'Score, evidence and share of the overall score per assessment area; unassessed skills and topics; recommendation cap.';
