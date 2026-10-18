-- Résumé-aware interviewing (AI_ARCHITECTURE.md section 2.13).

-- A candidate's résumé as the interview uses it: the verified structured profile, never the file or its full text. One per user;
-- an upload replaces the row (a new id), so an interview linked to the old one stops being personalised.
CREATE TABLE candidate_resumes (
    id                      uuid          NOT NULL,
    user_id                 uuid          NOT NULL,
    file_name               varchar(120)  NOT NULL,
    file_type               varchar(10)   NOT NULL,
    size_bytes              int           NOT NULL,
    page_count              smallint,
    text_chars              int           NOT NULL,
    profile                 jsonb         NOT NULL,
    injection_suspected     boolean       NOT NULL DEFAULT false,
    extraction_llm_call_id  uuid,
    model                   varchar(80)   NOT NULL,
    prompt_version          varchar(20)   NOT NULL,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_candidate_resumes PRIMARY KEY (id),
    CONSTRAINT uq_candidate_resumes_user UNIQUE (user_id),
    CONSTRAINT fk_candidate_resumes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_resumes_llm_call FOREIGN KEY (extraction_llm_call_id) REFERENCES llm_calls (id) ON DELETE SET NULL,
    CONSTRAINT ck_candidate_resumes_file_type CHECK (file_type IN ('PDF', 'DOCX', 'TEXT')),
    CONSTRAINT ck_candidate_resumes_size CHECK (size_bytes BETWEEN 1 AND 2097152),
    CONSTRAINT ck_candidate_resumes_pages CHECK (page_count IS NULL OR page_count BETWEEN 1 AND 10),
    CONSTRAINT ck_candidate_resumes_text_chars CHECK (text_chars BETWEEN 1 AND 30000)
);
CREATE INDEX ix_candidate_resumes_llm_call ON candidate_resumes (extraction_llm_call_id) WHERE extraction_llm_call_id IS NOT NULL;

-- The résumé an interview is personalised from; NULL without personalisation and once that résumé is deleted or replaced.
ALTER TABLE interviews ADD COLUMN resume_id uuid;
ALTER TABLE interviews ADD CONSTRAINT fk_interviews_resume FOREIGN KEY (resume_id) REFERENCES candidate_resumes (id) ON DELETE SET NULL;
CREATE INDEX ix_interviews_resume ON interviews (resume_id) WHERE resume_id IS NOT NULL;

-- The résumé experience (an id such as x3 in the profile) a technical question is anchored on, chosen by the engine.
ALTER TABLE questions ADD COLUMN resume_item_id varchar(10);
ALTER TABLE questions ADD CONSTRAINT ck_questions_resume_item
    CHECK (resume_item_id IS NULL OR (kind = 'TECHNICAL' AND resume_item_id ~ '^x[0-9]{1,2}$'));

-- The résumé is no longer a knowledge document (it is never chunked or embedded): the pointer was never used.
ALTER TABLE candidate_profiles DROP COLUMN resume_document_id;

COMMENT ON TABLE candidate_resumes IS 'Verified structured profile extracted from a candidate''s résumé. The uploaded file and its full text are never stored.';
COMMENT ON COLUMN candidate_resumes.profile IS 'Positions, years of experience, technologies, skills, domains and experiences (projects, responsibilities, achievements), each with the résumé excerpt it rests on.';
COMMENT ON COLUMN interviews.resume_id IS 'Résumé the interview is personalised from; NULL when not personalised or once that résumé is gone.';
COMMENT ON COLUMN questions.resume_item_id IS 'Résumé experience the question is anchored on; NULL otherwise.';
