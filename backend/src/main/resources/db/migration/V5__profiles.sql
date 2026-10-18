-- Candidate profiles and self-reported skills.

CREATE TABLE candidate_profiles (
    id                  uuid         NOT NULL,
    user_id             uuid         NOT NULL,
    headline            varchar(120),
    years_experience    smallint,
    job_title           varchar(60),
    resume_document_id  uuid,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_candidate_profiles PRIMARY KEY (id),
    CONSTRAINT uq_candidate_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_candidate_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_profiles_resume FOREIGN KEY (resume_document_id) REFERENCES knowledge_documents (id) ON DELETE SET NULL,
    CONSTRAINT ck_candidate_profiles_years_experience CHECK (years_experience BETWEEN 0 AND 60)
);
CREATE INDEX ix_candidate_profiles_resume ON candidate_profiles (resume_document_id) WHERE resume_document_id IS NOT NULL;

CREATE TABLE candidate_profile_skills (
    profile_id   uuid     NOT NULL,
    skill_id     bigint   NOT NULL,
    self_rating  smallint,
    CONSTRAINT pk_candidate_profile_skills PRIMARY KEY (profile_id, skill_id),
    CONSTRAINT fk_candidate_profile_skills_profile FOREIGN KEY (profile_id) REFERENCES candidate_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_profile_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id),
    CONSTRAINT ck_candidate_profile_skills_self_rating CHECK (self_rating BETWEEN 1 AND 5)
);

COMMENT ON COLUMN candidate_profiles.job_title IS 'Not named current_role: that is a reserved word in PostgreSQL.';
