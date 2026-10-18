-- Interview aggregate: configuration, interview, audit trail, questions, grounding sources, answers.
-- questions.generation_llm_call_id is a plain column here; V8 adds its foreign key once llm_calls exists.

CREATE TABLE interview_configurations (
    id                uuid         NOT NULL,
    owner_user_id     uuid         NOT NULL,
    role              varchar(30)  NOT NULL,
    experience_level  varchar(20)  NOT NULL,
    difficulty        varchar(10)  NOT NULL,
    duration_minutes  smallint     NOT NULL,
    interview_type    varchar(20)  NOT NULL,
    coding_language   varchar(20),
    use_resume        boolean      NOT NULL DEFAULT false,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_interview_configurations PRIMARY KEY (id),
    CONSTRAINT fk_interview_configurations_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_interview_configurations_role CHECK (role IN ('BACKEND', 'FRONTEND', 'FULL_STACK')),
    CONSTRAINT ck_interview_configurations_experience_level CHECK (experience_level IN ('INTERN', 'JUNIOR', 'MID', 'SENIOR', 'STAFF')),
    CONSTRAINT ck_interview_configurations_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT ck_interview_configurations_duration CHECK (duration_minutes IN (15, 30, 45, 60, 90)),
    CONSTRAINT ck_interview_configurations_type CHECK (interview_type IN ('TECHNICAL', 'CODING', 'SYSTEM_DESIGN', 'FULL_LOOP')),
    CONSTRAINT ck_interview_configurations_coding_language CHECK (interview_type IN ('TECHNICAL', 'SYSTEM_DESIGN') OR coding_language IS NOT NULL)
);
CREATE INDEX ix_interview_configurations_owner ON interview_configurations (owner_user_id);

CREATE TABLE interview_configuration_skills (
    configuration_id  uuid    NOT NULL,
    skill_id          bigint  NOT NULL,
    CONSTRAINT pk_interview_configuration_skills PRIMARY KEY (configuration_id, skill_id),
    CONSTRAINT fk_interview_configuration_skills_configuration FOREIGN KEY (configuration_id) REFERENCES interview_configurations (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_configuration_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id)
);

CREATE TABLE interviews (
    id                   uuid         NOT NULL,
    candidate_id         uuid         NOT NULL,
    configuration_id     uuid         NOT NULL,
    state                varchar(20)  NOT NULL,
    resume_state         varchar(20),
    current_question_id  uuid,
    plan                 jsonb        NOT NULL,
    memory               jsonb        NOT NULL DEFAULT '{}'::jsonb,
    started_at           timestamptz,
    deadline_at          timestamptz,
    ended_at             timestamptz,
    end_reason           varchar(20),
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_interviews PRIMARY KEY (id),
    CONSTRAINT fk_interviews_candidate FOREIGN KEY (candidate_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interviews_configuration FOREIGN KEY (configuration_id) REFERENCES interview_configurations (id),
    CONSTRAINT ck_interviews_state CHECK (state IN ('INIT', 'INTRODUCTION', 'QUESTIONING', 'FOLLOW_UP',
                                                    'CODING', 'SYSTEM_DESIGN', 'FINAL_EVALUATION', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_interviews_resume_state CHECK (resume_state IN ('QUESTIONING', 'CODING', 'SYSTEM_DESIGN')),
    CONSTRAINT ck_interviews_end_reason CHECK (end_reason IN ('PLAN_COMPLETE', 'TIME_UP', 'CANDIDATE_ENDED', 'ABANDONED'))
);
CREATE INDEX ix_interviews_candidate_created ON interviews (candidate_id, created_at DESC);
CREATE INDEX ix_interviews_configuration ON interviews (configuration_id);
CREATE INDEX ix_interviews_active ON interviews (state) WHERE state NOT IN ('COMPLETED', 'ABANDONED');

CREATE TABLE interview_state_transitions (
    id            bigint       GENERATED ALWAYS AS IDENTITY,
    interview_id  uuid         NOT NULL,
    from_state    varchar(20),
    to_state      varchar(20)  NOT NULL,
    event         varchar(30)  NOT NULL,
    details       jsonb,
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_interview_state_transitions PRIMARY KEY (id),
    CONSTRAINT fk_interview_state_transitions_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE CASCADE,
    CONSTRAINT ck_interview_state_transitions_from_state CHECK (from_state IN ('INIT', 'INTRODUCTION', 'QUESTIONING', 'FOLLOW_UP',
                                                    'CODING', 'SYSTEM_DESIGN', 'FINAL_EVALUATION', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_interview_state_transitions_to_state CHECK (to_state IN ('INIT', 'INTRODUCTION', 'QUESTIONING', 'FOLLOW_UP',
                                                    'CODING', 'SYSTEM_DESIGN', 'FINAL_EVALUATION', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_interview_state_transitions_creation CHECK (from_state IS NOT NULL OR to_state = 'INIT')
);
CREATE INDEX ix_interview_state_transitions_interview ON interview_state_transitions (interview_id, occurred_at);

CREATE TABLE questions (
    id                      uuid         NOT NULL,
    interview_id            uuid         NOT NULL,
    parent_question_id      uuid,
    sequence_no             int          NOT NULL,
    section                 varchar(20)  NOT NULL,
    kind                    varchar(20)  NOT NULL,
    topic_id                bigint,
    difficulty              smallint     NOT NULL,
    followup_depth          smallint     NOT NULL DEFAULT 0,
    prompt                  text         NOT NULL,
    details                 jsonb,
    expected_key_points     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    rubric_id               varchar(40)  NOT NULL,
    source                  varchar(20)  NOT NULL,
    generation_llm_call_id  uuid,
    embedding               vector(768),
    embedding_model         varchar(80),
    asked_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_questions PRIMARY KEY (id),
    CONSTRAINT uq_questions_interview_sequence UNIQUE (interview_id, sequence_no),
    CONSTRAINT fk_questions_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE CASCADE,
    CONSTRAINT fk_questions_parent FOREIGN KEY (parent_question_id) REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_questions_topic FOREIGN KEY (topic_id) REFERENCES topics (id),
    CONSTRAINT ck_questions_section CHECK (section IN ('INTRODUCTION', 'QUESTIONING', 'CODING', 'SYSTEM_DESIGN')),
    CONSTRAINT ck_questions_kind CHECK (kind IN ('INTRO', 'TECHNICAL', 'FOLLOW_UP', 'CODING', 'SYSTEM_DESIGN')),
    CONSTRAINT ck_questions_difficulty CHECK (difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_questions_followup_depth CHECK (followup_depth BETWEEN 0 AND 2),
    CONSTRAINT ck_questions_source CHECK (source IN ('GENERATED', 'EXEMPLAR_FALLBACK', 'STATIC')),
    CONSTRAINT ck_questions_embedding_model CHECK ((embedding IS NULL) = (embedding_model IS NULL))
);
CREATE INDEX ix_questions_parent ON questions (parent_question_id) WHERE parent_question_id IS NOT NULL;

ALTER TABLE interviews ADD CONSTRAINT fk_interviews_current_question
    FOREIGN KEY (current_question_id) REFERENCES questions (id) ON DELETE SET NULL;
CREATE INDEX ix_interviews_current_question ON interviews (current_question_id) WHERE current_question_id IS NOT NULL;

CREATE TABLE question_sources (
    question_id  uuid      NOT NULL,
    chunk_id     uuid      NOT NULL,
    rank         smallint  NOT NULL,
    fused_score  real      NOT NULL,
    CONSTRAINT pk_question_sources PRIMARY KEY (question_id, chunk_id),
    CONSTRAINT fk_question_sources_question FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_question_sources_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunks (id) ON DELETE CASCADE,
    CONSTRAINT ck_question_sources_rank CHECK (rank >= 1)
);
CREATE INDEX ix_question_sources_chunk ON question_sources (chunk_id);

CREATE TABLE answers (
    id                  uuid         NOT NULL,
    question_id         uuid         NOT NULL,
    interview_id        uuid         NOT NULL,
    content             text         NOT NULL,
    code_language       varchar(20),
    skipped             boolean      NOT NULL DEFAULT false,
    time_taken_seconds  int,
    evaluation_status   varchar(20)  NOT NULL DEFAULT 'PENDING',
    submitted_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_answers PRIMARY KEY (id),
    CONSTRAINT uq_answers_question UNIQUE (question_id),
    CONSTRAINT fk_answers_question FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_answers_interview FOREIGN KEY (interview_id) REFERENCES interviews (id) ON DELETE CASCADE,
    CONSTRAINT ck_answers_content_length CHECK (char_length(content) <= 20000),
    CONSTRAINT ck_answers_time_taken CHECK (time_taken_seconds >= 0),
    CONSTRAINT ck_answers_evaluation_status CHECK (evaluation_status IN ('PENDING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_answers_interview ON answers (interview_id);
CREATE INDEX ix_answers_pending ON answers (interview_id) WHERE evaluation_status <> 'COMPLETED';

COMMENT ON TABLE interviews IS 'Aggregate root. version is the optimistic-locking counter; plan is immutable after creation; memory is rewritten after every evaluation.';
COMMENT ON COLUMN interviews.resume_state IS 'Section to return to after FOLLOW_UP.';
COMMENT ON COLUMN questions.expected_key_points IS 'Server-only; never sent to the client.';
COMMENT ON COLUMN questions.generation_llm_call_id IS 'References llm_calls(id); the foreign key is added in V8 because llm_calls is created last.';
COMMENT ON COLUMN answers.question_id IS 'Unique: the natural idempotency key for answer submission.';
COMMENT ON TABLE question_sources IS 'Retrieved chunks that grounded a generated question.';
