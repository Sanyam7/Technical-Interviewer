package com.techinterviewer.persistence;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.evaluation.repository.InterviewReportRepository;
import com.techinterviewer.evaluation.repository.ReportSkillScoreRepository;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import com.techinterviewer.interview.repository.QuestionGroundingRepository;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import com.techinterviewer.rag.repository.KnowledgeChunkRepository;
import com.techinterviewer.rag.repository.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the persistence integration tests: the whole application context against a real PostgreSQL, so Flyway
 * builds the schema and Hibernate validates every entity against it ({@code ddl-auto=validate}). Each
 * {@link #inTransaction} call is its own persistence context, so reading back after a write always goes to the
 * database, never to a first-level cache.
 *
 * <p>The tables of users and the skill catalog are owned by other parts of the application and have no entity
 * here, so the tests create users with SQL and take the seeded skills as they are. Users created by a test are
 * deleted afterwards, which removes everything that belongs to them through the foreign key cascades; public
 * knowledge documents have no owner and are removed individually.
 */
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.techinterviewer.persistence.RecordingStatementInspector")
@Import(TestcontainersConfiguration.class)
public abstract class PersistenceTestBase {

    private static final List<UUID> MODEL_CALLS =
            List.of(Fixtures.QUESTION_LLM_CALL, Fixtures.EVALUATION_LLM_CALL, Fixtures.REPORT_LLM_CALL);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TransactionTemplate transaction;

    @Autowired
    protected InterviewRepository interviews;

    @Autowired
    protected InterviewConfigurationRepository configurations;

    @Autowired
    protected InterviewQuestionRepository questions;

    @Autowired
    protected CandidateAnswerRepository answers;

    @Autowired
    protected InterviewStateTransitionRepository transitions;

    @Autowired
    protected QuestionGroundingRepository groundings;

    @Autowired
    protected EvaluationRepository evaluations;

    @Autowired
    protected InterviewReportRepository reports;

    @Autowired
    protected ReportSkillScoreRepository skillScores;

    @Autowired
    protected KnowledgeDocumentRepository documents;

    @Autowired
    protected KnowledgeChunkRepository chunks;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdPublicDocuments = new ArrayList<>();

    @BeforeEach
    void prepareDatabase() {
        for (UUID call : MODEL_CALLS) {
            jdbc.update(
                    "insert into llm_calls (id, task, provider, model, status, latency_ms) values (?, 'TEST', 'test', 'test', 'OK', 1)"
                            + " on conflict do nothing", call);
        }
        RecordingStatementInspector.clear();
    }

    @AfterEach
    void deleteCreatedRows() {
        jdbc.update("delete from llm_calls where task = 'TEST'");
        for (UUID document : createdPublicDocuments) {
            jdbc.update("delete from knowledge_documents where id = ?", document);
        }
        createdPublicDocuments.clear();
        for (UUID user : createdUsers) {
            jdbc.update("delete from users where id = ?", user);
        }
        createdUsers.clear();
    }

    /** Inserts a user row (candidate role) and returns its id. */
    protected UUID newUser() {
        UUID id = UuidV7.generate();
        jdbc.update(
                "insert into users (id, email, password_hash, display_name, role) values (?, ?, 'x', 'Test User', 'CANDIDATE')",
                id, id + "@example.test");
        createdUsers.add(id);
        return id;
    }

    /** Ids of the first {@code count} seeded skills (ascending). */
    protected List<Long> skillIds(int count) {
        List<Long> ids = jdbc.queryForList("select id from skills order by id limit ?", Long.class, count);
        if (ids.size() < count) {
            throw new IllegalStateException("the seeded catalog has fewer than " + count + " skills");
        }
        return ids;
    }

    /** Id of a seeded topic. */
    protected long topicId() {
        return jdbc.queryForObject("select id from topics order by id limit 1", Long.class);
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    /** Runs the work in a second, independent transaction (its own persistence context) while another one is open. */
    protected <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate independent = new TransactionTemplate(transaction.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return independent.execute(status -> work.get());
    }

    protected void inTransaction(Runnable work) {
        transaction.executeWithoutResult(status -> work.run());
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    protected int count(String table, String column, Object value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    }

    /** A candidate with a saved configuration, an INIT interview and its creation row. */
    protected record Scenario(UUID userId, InterviewConfiguration configuration, Interview interview) {

        public UUID interviewId() {
            return interview.getId();
        }
    }

    protected Scenario newScenario() {
        UUID user = newUser();
        InterviewConfiguration configuration = Fixtures.configuration(user, Set.copyOf(skillIds(2)));
        Interview interview = Fixtures.interview(user, configuration.getId());
        inTransaction(() -> {
            configurations.saveAndFlush(configuration);
            interviews.saveAndFlush(interview);
            transitions.saveAndFlush(InterviewStateTransition.creation(interview.getId(), Fixtures.NOW));
        });
        return new Scenario(user, configuration, interview);
    }

    protected InterviewQuestion saveQuestion(Scenario scenario, int sequenceNo) {
        InterviewQuestion question = Fixtures.technicalQuestion(scenario.interviewId(), sequenceNo, null);
        inTransaction(() -> questions.saveAndFlush(question));
        return question;
    }

    protected CandidateAnswer saveAnswer(Scenario scenario, InterviewQuestion question) {
        CandidateAnswer answer = Fixtures.answer(question.getId(), scenario.interviewId());
        inTransaction(() -> answers.saveAndFlush(answer));
        return answer;
    }

    /** A PUBLIC curated document with one chunk; the document is removed after the test. */
    protected KnowledgeChunk saveChunkOfPublicDocument(char shaSeed) {
        KnowledgeDocument document = KnowledgeDocument.curated(
                "corpus/" + shaSeed + ".md", DocumentType.REFERENCE, null,
                Fixtures.documentContent(Fixtures.sha(shaSeed)), Map.of(), Fixtures.NOW);
        KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), 0, null, "text", 1, null, null, Map.of(), Fixtures.NOW);
        createdPublicDocuments.add(document.getId());
        inTransaction(() -> {
            documents.saveAndFlush(document);
            chunks.saveAndFlush(chunk);
        });
        return chunk;
    }

    /** Remembers a PUBLIC document to be deleted after the test (private ones go with their owner). */
    protected void deleteAfterTest(KnowledgeDocument publicDocument) {
        createdPublicDocuments.add(publicDocument.getId());
    }
}
