-- System design interview mode (AI_ARCHITECTURE.md section 2.12).

-- The curated problem a design question was taken from (resources/design-problems/<id>.json). NULL for every other question,
-- and for design problems the AI wrote.
ALTER TABLE questions ADD COLUMN design_problem_id varchar(60);
ALTER TABLE questions ADD CONSTRAINT ck_questions_design_problem
    CHECK (design_problem_id IS NULL OR (kind = 'SYSTEM_DESIGN' AND source = 'STATIC'));

-- The design dimension a follow-up of a design thread probes (a rubric criterion such as CACHING). The engine reads the
-- dimensions a thread probed already so it never asks about one twice. The values are the application's Criterion names,
-- which may grow without a migration, so they are not listed here.
ALTER TABLE questions ADD COLUMN probe_criterion varchar(30);
ALTER TABLE questions ADD CONSTRAINT ck_questions_probe_criterion
    CHECK (probe_criterion IS NULL OR (kind = 'FOLLOW_UP' AND section = 'SYSTEM_DESIGN'));

-- A design thread is a discussion of up to six probes; other threads keep at most two follow-ups (checked by the application).
ALTER TABLE questions DROP CONSTRAINT ck_questions_followup_depth;
ALTER TABLE questions ADD CONSTRAINT ck_questions_followup_depth CHECK (followup_depth BETWEEN 0 AND 6);

-- The architecture diagram drawn with a design answer: components and connections. NULL for other answers and when none was drawn.
ALTER TABLE answers ADD COLUMN architecture jsonb;

COMMENT ON COLUMN questions.design_problem_id IS 'Curated system design problem the question was taken from; NULL otherwise.';
COMMENT ON COLUMN questions.probe_criterion IS 'Design dimension a design-thread follow-up probes, chosen by the engine; NULL otherwise.';
COMMENT ON COLUMN answers.architecture IS 'Architecture diagram of a design answer: {components: [{id, kind, name, notes}], connections: [{from, to, label, async}]}.';
