-- Catalog: skills and topics. Rows are seeded by the repeatable migration R__seed_catalog.sql.

CREATE TABLE skills (
    id          bigint       GENERATED ALWAYS AS IDENTITY,
    slug        varchar(60)  NOT NULL,
    name        varchar(80)  NOT NULL,
    category    varchar(20)  NOT NULL,
    description text,
    active      boolean      NOT NULL DEFAULT true,
    CONSTRAINT pk_skills PRIMARY KEY (id),
    CONSTRAINT uq_skills_slug UNIQUE (slug),
    CONSTRAINT ck_skills_category CHECK (category IN ('LANGUAGE', 'FRAMEWORK', 'DATABASE', 'CLOUD', 'CONCEPT', 'TOOL'))
);

CREATE TABLE topics (
    id              bigint        GENERATED ALWAYS AS IDENTITY,
    skill_id        bigint        NOT NULL,
    slug            varchar(80)   NOT NULL,
    name            varchar(120)  NOT NULL,
    description     text,
    min_difficulty  smallint      NOT NULL DEFAULT 1,
    max_difficulty  smallint      NOT NULL DEFAULT 5,
    weight          numeric(4, 2) NOT NULL DEFAULT 1.0,
    CONSTRAINT pk_topics PRIMARY KEY (id),
    CONSTRAINT uq_topics_skill_slug UNIQUE (skill_id, slug),
    CONSTRAINT fk_topics_skill FOREIGN KEY (skill_id) REFERENCES skills (id),
    CONSTRAINT ck_topics_min_difficulty CHECK (min_difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_topics_max_difficulty CHECK (max_difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_topics_difficulty_order CHECK (min_difficulty <= max_difficulty),
    CONSTRAINT ck_topics_weight CHECK (weight > 0)
);
