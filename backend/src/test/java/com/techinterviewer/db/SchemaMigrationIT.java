package com.techinterviewer.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Flyway-built schema against DATABASE_SCHEMA.md on a real PostgreSQL + pgvector instance.
 * Each test commits its own rows (so a rejected statement cannot poison a shared transaction)
 * and removes them in {@link #cleanUp()}; deleting the users cascades to everything they own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationIT {

    private static final String SHA = "a".repeat(64);

    private static final List<String> EXPECTED_TABLES = List.of(
            "answers", "candidate_profile_skills", "candidate_profiles", "candidate_resumes", "code_test_runs", "embeddings", "evaluations",
            "interview_configuration_skills", "interview_configurations", "interview_state_transitions",
            "interviews", "knowledge_chunks", "knowledge_documents", "llm_calls", "question_sources",
            "questions", "refresh_tokens", "report_skill_scores", "reports", "skills", "topics", "users");

    /** One row of the catalogue: a skill with one of its topics. */
    private record SeedTopic(String skillSlug, String skillCategory, String topicSlug, int minDifficulty, int maxDifficulty) {
    }

    /** Mirrors the table in DATABASE_SCHEMA.md section 3.3: six skills and 37 topics. */
    private static final List<SeedTopic> EXPECTED_SEED = List.of(
            new SeedTopic("java", "LANGUAGE", "core-language-oop", 1, 3),
            new SeedTopic("java", "LANGUAGE", "collections", 1, 4),
            new SeedTopic("java", "LANGUAGE", "generics", 2, 4),
            new SeedTopic("java", "LANGUAGE", "exceptions", 1, 3),
            new SeedTopic("java", "LANGUAGE", "streams-functional", 2, 4),
            new SeedTopic("java", "LANGUAGE", "concurrency", 2, 5),
            new SeedTopic("java", "LANGUAGE", "jvm-memory-gc", 3, 5),

            new SeedTopic("spring-boot", "FRAMEWORK", "dependency-injection-proxies", 1, 4),
            new SeedTopic("spring-boot", "FRAMEWORK", "configuration-profiles", 1, 3),
            new SeedTopic("spring-boot", "FRAMEWORK", "web-mvc-rest", 1, 4),
            new SeedTopic("spring-boot", "FRAMEWORK", "data-jpa", 2, 5),
            new SeedTopic("spring-boot", "FRAMEWORK", "transactions", 2, 5),
            new SeedTopic("spring-boot", "FRAMEWORK", "security", 2, 5),
            new SeedTopic("spring-boot", "FRAMEWORK", "testing", 2, 4),

            new SeedTopic("postgresql", "DATABASE", "sql-fundamentals", 1, 3),
            new SeedTopic("postgresql", "DATABASE", "schema-design-normalization", 1, 4),
            new SeedTopic("postgresql", "DATABASE", "indexing", 2, 5),
            new SeedTopic("postgresql", "DATABASE", "query-planning", 3, 5),
            new SeedTopic("postgresql", "DATABASE", "transactions-isolation", 2, 5),
            new SeedTopic("postgresql", "DATABASE", "locking-concurrency", 3, 5),

            new SeedTopic("system-design", "CONCEPT", "scalability-fundamentals", 1, 3),
            new SeedTopic("system-design", "CONCEPT", "caching", 2, 4),
            new SeedTopic("system-design", "CONCEPT", "partitioning-replication", 3, 5),
            new SeedTopic("system-design", "CONCEPT", "messaging-async", 2, 5),
            new SeedTopic("system-design", "CONCEPT", "api-design-rate-limiting", 2, 4),
            new SeedTopic("system-design", "CONCEPT", "reliability-observability", 3, 5),

            new SeedTopic("react", "FRAMEWORK", "components-props-state", 1, 3),
            new SeedTopic("react", "FRAMEWORK", "hooks", 1, 4),
            new SeedTopic("react", "FRAMEWORK", "effects-data-fetching", 2, 4),
            new SeedTopic("react", "FRAMEWORK", "rendering-reconciliation", 2, 5),
            new SeedTopic("react", "FRAMEWORK", "performance", 3, 5),
            new SeedTopic("react", "FRAMEWORK", "state-management", 2, 4),

            new SeedTopic("typescript", "LANGUAGE", "type-system-basics", 1, 3),
            new SeedTopic("typescript", "LANGUAGE", "narrowing-type-guards", 2, 4),
            new SeedTopic("typescript", "LANGUAGE", "generics", 2, 5),
            new SeedTopic("typescript", "LANGUAGE", "utility-mapped-conditional-types", 3, 5),
            new SeedTopic("typescript", "LANGUAGE", "strictness-tooling", 1, 3),
            new SeedTopic("javascript", "LANGUAGE", "language-core", 1, 4),
            new SeedTopic("javascript", "LANGUAGE", "async-event-loop", 2, 5),
            new SeedTopic("rest-api", "CONCEPT", "http-api-design", 1, 4),
            new SeedTopic("microservices", "CONCEPT", "architecture", 2, 5));

    @Autowired
    JdbcClient jdbc;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    String ddlAuto;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM users WHERE email LIKE 'it-%@example.com'").update();
    }

    @Test
    void flywayAppliedEveryMigrationSuccessfully() {
        List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank")
                .query(String.class).list();
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");

        Integer failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success").query(Integer.class).single();
        assertThat(failed).isZero();

        Integer repeatable = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version IS NULL AND success").query(Integer.class).single();
        assertThat(repeatable).isEqualTo(1);
    }

    @Test
    void schemaContainsExactlyTheDocumentedTables() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();
        assertThat(tables).containsExactlyElementsOf(EXPECTED_TABLES);
    }

    @Test
    void requiredExtensionsAreInstalled() {
        String vector = jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'").query(String.class).single();
        String[] parts = vector.split("\\.");
        assertThat(Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1])).as("pgvector version %s", vector).isGreaterThanOrEqualTo(8);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'citext'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void catalogSeedContainsExactlyTheDocumentedSkillsAndTopics() {
        List<SeedTopic> seeded = jdbc.sql("""
                        SELECT s.slug AS skill_slug, s.category, t.slug AS topic_slug, t.min_difficulty, t.max_difficulty
                        FROM skills s JOIN topics t ON t.skill_id = s.id""")
                .query((rs, n) -> new SeedTopic(rs.getString("skill_slug"), rs.getString("category"),
                        rs.getString("topic_slug"), rs.getInt("min_difficulty"), rs.getInt("max_difficulty")))
                .list();
        assertThat(seeded).hasSize(41).containsExactlyInAnyOrderElementsOf(EXPECTED_SEED);

        List<String> skills = jdbc.sql("SELECT slug || ':' || category FROM skills").query(String.class).list();
        assertThat(skills).containsExactlyInAnyOrder(
                "java:LANGUAGE", "spring-boot:FRAMEWORK", "postgresql:DATABASE", "system-design:CONCEPT",
                "react:FRAMEWORK", "typescript:LANGUAGE", "javascript:LANGUAGE", "rest-api:CONCEPT", "microservices:CONCEPT");

        Integer inactiveOrIncomplete = jdbc.sql("""
                        SELECT (SELECT count(*) FROM skills WHERE NOT active OR name = '' OR description IS NULL)
                             + (SELECT count(*) FROM topics WHERE name = '' OR description IS NULL OR min_difficulty > max_difficulty)""")
                .query(Integer.class).single();
        assertThat(inactiveOrIncomplete).isZero();
    }

    @Test
    void emailsAreUniqueCaseInsensitively() {
        insertUser("it-ada@example.com");

        assertThatThrownBy(() -> insertUser("IT-ADA@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email");
    }

    @Test
    void checkConstraintsRejectInvalidValues() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, role)
                        VALUES (:id, 'it-role@example.com', '{bcrypt}x', 'Role', 'SUPERUSER')""")
                .param("id", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_role");

        UUID user = insertUser("it-hash@example.com");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at)
                        VALUES (:id, :user, :family, 'abc', now() + interval '1 day')""")
                .param("id", UUID.randomUUID()).param("user", user).param("family", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_refresh_tokens_token_hash_hex");
    }

    @Test
    void privateDocumentsRequireAnOwner() {
        assertThatThrownBy(() -> insertDocument(null, "PRIVATE", "RESUME", SHA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_knowledge_documents_private_has_owner");
    }

    @Test
    void curatedDocumentsAreUniquePerContentHashEvenWithoutAnOwner() {
        UUID first = insertDocument(null, "PUBLIC", "CURATED", "b".repeat(64));
        try {
            assertThatThrownBy(() -> insertDocument(null, "PUBLIC", "CURATED", "b".repeat(64)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_knowledge_documents_owner_sha256");
        } finally {
            jdbc.sql("DELETE FROM knowledge_documents WHERE id = :id").param("id", first).update();
        }
    }

    @Test
    void resumesMustBePrivateBothBySourceTypeAndByDocType() {
        UUID owner = insertUser("it-resume@example.com");

        assertThatThrownBy(() -> insertDocument(owner, "PUBLIC", "RESUME", "RESUME", "d".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_knowledge_documents_resume_private");
        assertThatThrownBy(() -> insertDocument(owner, "PUBLIC", "CURATED", "RESUME", "e".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_knowledge_documents_resume_private");
        assertThatThrownBy(() -> insertDocument(owner, "PUBLIC", "RESUME", "REFERENCE", "f".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_knowledge_documents_resume_private");

        insertDocument(owner, "PRIVATE", "RESUME", "RESUME", "1".repeat(64));
        insertDocument(owner, "PRIVATE", "CURATED", "RESUME", "2".repeat(64));
        UUID curated = insertDocument(null, "PUBLIC", "CURATED", "REFERENCE", "3".repeat(64));
        jdbc.sql("DELETE FROM knowledge_documents WHERE id = :id").param("id", curated).update();
    }

    @ParameterizedTest(name = "{0} without a coding language is {1}")
    @CsvSource({"TECHNICAL, accepted", "SYSTEM_DESIGN, accepted", "CODING, rejected", "FULL_LOOP, rejected"})
    void codingLanguageIsRequiredOnlyForInterviewTypesThatIncludeCoding(String type, String outcome) {
        UUID owner = insertUser("it-config-" + type.toLowerCase().replace('_', '-') + "@example.com");

        if (outcome.equals("accepted")) {
            insertConfiguration(owner, type, null);
        } else {
            assertThatThrownBy(() -> insertConfiguration(owner, type, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_interview_configurations_coding_language");
            insertConfiguration(owner, type, "JAVA");
        }
    }

    @Test
    void creatingAnInterviewIsTheOnlyTransitionWithoutAPreviousState() {
        UUID interview = insertInterview(insertUser("it-transition@example.com"));

        insertTransition(interview, null, "INIT", "CREATED");
        assertThatThrownBy(() -> insertTransition(interview, null, "QUESTIONING", "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_interview_state_transitions_creation");
        insertTransition(interview, "INIT", "INTRODUCTION", "STARTED");

        assertThat(jdbc.sql("SELECT count(*) FROM interview_state_transitions WHERE interview_id = :id")
                .param("id", interview).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aQuestionEmbeddingAndItsModelAreStoredTogether() {
        UUID interview = insertInterview(insertUser("it-question@example.com"));

        insertQuestion(interview, 1, null, null);
        insertQuestion(interview, 2, vectorLiteral(768, 0), "model-a");
        assertThatThrownBy(() -> insertQuestion(interview, 3, vectorLiteral(768, 0), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_questions_embedding_model");
        assertThatThrownBy(() -> insertQuestion(interview, 4, null, "model-a"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_questions_embedding_model");
    }

    @Test
    void vectorColumnsAcceptOnly768Dimensions() {
        UUID chunk = insertChunkWithEmbedding(insertUser("it-dim@example.com"), "model-a", vectorLiteral(768, 0));

        assertThat(jdbc.sql("SELECT count(*) FROM embeddings WHERE chunk_id = :c").param("c", chunk).query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> insertEmbedding(chunk, "model-b", vectorLiteral(3, 0)))
                .hasMessageContaining("expected 768 dimensions");
    }

    @Test
    void oneEmbeddingPerChunkAndModel() {
        UUID chunk = insertChunkWithEmbedding(insertUser("it-model@example.com"), "model-a", vectorLiteral(768, 0));

        assertThatThrownBy(() -> insertEmbedding(chunk, "model-a", vectorLiteral(768, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_embeddings_chunk_model");
        insertEmbedding(chunk, "model-b", vectorLiteral(768, 1));
    }

    @Test
    void cosineDistanceOrdersNearestFirstAndTheHnswIndexExists() {
        UUID owner = insertUser("it-knn@example.com");
        UUID near = insertChunkWithEmbedding(owner, "model-knn", vectorLiteral(768, 0));
        UUID far = insertChunkWithEmbedding(owner, "model-knn", vectorLiteral(768, 5));

        List<UUID> ranked = jdbc.sql("""
                        SELECT chunk_id FROM embeddings WHERE model = 'model-knn'
                        ORDER BY embedding <=> :query::vector LIMIT 2""")
                .param("query", vectorLiteral(768, 0)).query(UUID.class).list();
        assertThat(ranked).containsExactly(near, far);

        String indexDef = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_embeddings_hnsw'").query(String.class).single();
        assertThat(indexDef).contains("USING hnsw").contains("vector_cosine_ops");
    }

    @Test
    void generatedTsvectorSupportsKeywordSearchOverContentAndHeading() {
        UUID owner = insertUser("it-fts@example.com");
        UUID doc = insertDocument(owner, "PRIVATE", "RESUME", "c".repeat(64));
        jdbc.sql("""
                        INSERT INTO knowledge_chunks (id, document_id, chunk_index, heading_path, content, token_count)
                        VALUES (:id, :doc, 0, 'Java > Concurrency > volatile', 'The keyword guarantees visibility between threads.', 8)""")
                .param("id", UUID.randomUUID()).param("doc", doc).update();

        Integer byContent = jdbc.sql("SELECT count(*) FROM knowledge_chunks WHERE content_tsv @@ websearch_to_tsquery('english', 'visibility thread')")
                .query(Integer.class).single();
        Integer byHeading = jdbc.sql("SELECT count(*) FROM knowledge_chunks WHERE content_tsv @@ websearch_to_tsquery('english', 'concurrency')")
                .query(Integer.class).single();
        assertThat(byContent).isEqualTo(1);
        assertThat(byHeading).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE knowledge_chunks SET content_tsv = to_tsvector('x') WHERE document_id = :d").param("d", doc).update())
                .hasMessageContaining("content_tsv");
    }

    @Test
    void deletingAUserCascadesToTheirPrivateKnowledge() {
        UUID owner = insertUser("it-cascade@example.com");
        UUID chunk = insertChunkWithEmbedding(owner, "model-a", vectorLiteral(768, 0));

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", owner).update();

        assertThat(count("knowledge_documents WHERE owner_user_id = :id", owner)).isZero();
        assertThat(count("knowledge_chunks WHERE id = :id", chunk)).isZero();
        assertThat(count("embeddings WHERE chunk_id = :id", chunk)).isZero();
    }

    @Test
    void hibernateValidatesButNeverGeneratesTheSchema() {
        assertThat(ddlAuto).isEqualTo("validate");
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, role)
                        VALUES (:id, :email, '{bcrypt}placeholder', 'Test User', 'CANDIDATE')""")
                .param("id", id).param("email", email).update();
        return id;
    }

    private UUID insertDocument(UUID owner, String visibility, String sourceType, String sha) {
        return insertDocument(owner, visibility, sourceType, "RESUME".equals(sourceType) ? "RESUME" : "REFERENCE", sha);
    }

    private UUID insertDocument(UUID owner, String visibility, String sourceType, String docType, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO knowledge_documents (id, title, source_type, doc_type, visibility, owner_user_id,
                                                         mime_type, byte_size, content_sha256, extracted_text, status)
                        VALUES (:id, 'Doc', :source, :docType, :visibility, :owner, 'text/plain', 10, :sha, 'text', 'READY')""")
                .param("id", id).param("source", sourceType).param("docType", docType)
                .param("visibility", visibility).param("owner", owner).param("sha", sha).update();
        return id;
    }

    private UUID insertConfiguration(UUID owner, String interviewType, String codingLanguage) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO interview_configurations (id, owner_user_id, role, experience_level, difficulty,
                                                              duration_minutes, interview_type, coding_language)
                        VALUES (:id, :owner, 'BACKEND', 'MID', 'MEDIUM', 45, :type, :language)""")
                .param("id", id).param("owner", owner).param("type", interviewType).param("language", codingLanguage).update();
        return id;
    }

    private UUID insertInterview(UUID owner) {
        UUID configuration = insertConfiguration(owner, "TECHNICAL", null);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO interviews (id, candidate_id, configuration_id, state, plan)
                        VALUES (:id, :candidate, :configuration, 'INIT', '{}'::jsonb)""")
                .param("id", id).param("candidate", owner).param("configuration", configuration).update();
        return id;
    }

    private void insertTransition(UUID interview, String from, String to, String event) {
        jdbc.sql("""
                        INSERT INTO interview_state_transitions (interview_id, from_state, to_state, event)
                        VALUES (:interview, :from, :to, :event)""")
                .param("interview", interview).param("from", from).param("to", to).param("event", event).update();
    }

    private void insertQuestion(UUID interview, int sequenceNo, String vector, String embeddingModel) {
        jdbc.sql("""
                        INSERT INTO questions (id, interview_id, sequence_no, section, kind, difficulty, prompt, rubric_id,
                                               source, embedding, embedding_model)
                        VALUES (:id, :interview, :sequenceNo, 'QUESTIONING', 'TECHNICAL', 3, 'Explain volatile.', 'technical-v1',
                                'GENERATED', :vector::vector, :model)""")
                .param("id", UUID.randomUUID()).param("interview", interview).param("sequenceNo", sequenceNo)
                .param("vector", vector).param("model", embeddingModel).update();
    }

    private UUID insertChunkWithEmbedding(UUID owner, String model, String vector) {
        UUID doc = insertDocument(owner, "PRIVATE", "RESUME", UUID.randomUUID().toString().replace("-", "").repeat(2));
        UUID chunk = UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_chunks (id, document_id, chunk_index, content, token_count) VALUES (:id, :doc, 0, 'content', 1)")
                .param("id", chunk).param("doc", doc).update();
        insertEmbedding(chunk, model, vector);
        return chunk;
    }

    private void insertEmbedding(UUID chunk, String model, String vector) {
        jdbc.sql("""
                        INSERT INTO embeddings (id, chunk_id, model, dimensions, embedding, content_sha256)
                        VALUES (:id, :chunk, :model, 768, :vector::vector, :sha)""")
                .param("id", UUID.randomUUID()).param("chunk", chunk).param("model", model)
                .param("vector", vector).param("sha", SHA).update();
    }

    private int count(String fromClause, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + fromClause).param("id", id).query(Integer.class).single();
    }

    /** A vector whose only non-zero component is at {@code hotIndex}; orthogonal vectors have cosine distance 1. */
    private static String vectorLiteral(int dimensions, int hotIndex) {
        return IntStream.range(0, dimensions)
                .mapToObj(i -> i == hotIndex ? "1" : "0")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
