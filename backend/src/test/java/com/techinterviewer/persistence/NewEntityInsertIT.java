package com.techinterviewer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.report.InterviewReport;
import com.techinterviewer.evaluation.report.ReportSkillScore;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.QuestionGrounding;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import com.techinterviewer.rag.domain.KnowledgeDocument;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Saving a new entity must be a plain INSERT: the application assigns the UUIDv7 and {@code Persistable.isNew()} tells
 * Spring Data so, which spares the {@code SELECT} that {@code merge} would issue first. Proven for every entity,
 * with {@code save} (not {@code saveAndFlush}) inside a transaction, as the services use it.
 */
class NewEntityInsertIT extends PersistenceTestBase {

    private List<String> statementsDuring(Runnable work) {
        RecordingStatementInspector.clear();
        inTransaction(work);
        return RecordingStatementInspector.statements();
    }

    private void assertOnlyInserts(List<String> statements, String... tables) {
        assertThat(statements).as("exactly one insert per table and nothing else").hasSize(tables.length);
        for (int i = 0; i < tables.length; i++) {
            assertThat(statements.get(i)).startsWith("insert into " + tables[i] + " ");
        }
    }

    @Test
    void anInterviewIsInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        Interview fresh = Fixtures.interview(scenario.userId(), scenario.configuration().getId());

        assertOnlyInserts(statementsDuring(() -> interviews.save(fresh)), "interviews");
    }

    @Test
    void aConfigurationIsInsertedWithItsSkillRowsAndWithoutASelect() {
        UUID owner = newUser();
        InterviewConfiguration fresh = Fixtures.configuration(owner, Set.copyOf(skillIds(2)));

        List<String> statements = statementsDuring(() -> configurations.save(fresh));

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).startsWith("insert into interview_configurations ");
        assertThat(statements.subList(1, 3)).allSatisfy(sql -> assertThat(sql).startsWith("insert into interview_configuration_skills "));
    }

    @Test
    void aQuestionIsInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        InterviewQuestion fresh = Fixtures.technicalQuestion(scenario.interviewId(), 1, null);

        assertOnlyInserts(statementsDuring(() -> questions.save(fresh)), "questions");
    }

    @Test
    void anAnswerIsInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer fresh = Fixtures.answer(question.getId(), scenario.interviewId());

        assertOnlyInserts(statementsDuring(() -> answers.save(fresh)), "answers");
    }

    @Test
    void anEvaluationIsInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation fresh = Fixtures.evaluation(answer.getId());

        assertOnlyInserts(statementsDuring(() -> evaluations.save(fresh)), "evaluations");
    }

    @Test
    void aTransitionIsInsertedWithoutASelectOrASecondStatementForItsIdentity() {
        Scenario scenario = newScenario();
        InterviewStateTransition fresh = InterviewStateTransition.creation(scenario.interviewId(), Fixtures.NOW);

        assertOnlyInserts(statementsDuring(() -> transitions.save(fresh)), "interview_state_transitions");
        assertThat(fresh.getId()).isNotNull();
    }

    @Test
    void aGroundingWithACompositeKeyIsInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        KnowledgeChunk chunk = saveChunkOfPublicDocument('a');
        QuestionGrounding fresh = new QuestionGrounding(question.getId(), chunk.getId(), 1, 0.5f);

        assertOnlyInserts(statementsDuring(() -> groundings.save(fresh)), "question_sources");
    }

    @Test
    void aReportAndASkillScoreAreInsertedWithoutASelect() {
        Scenario scenario = newScenario();
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        ReportSkillScore score = new ReportSkillScore(report.getId(), skillIds(1).getFirst(), new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE);

        assertOnlyInserts(statementsDuring(() -> reports.save(report)), "reports");
        assertOnlyInserts(statementsDuring(() -> skillScores.save(score)), "report_skill_scores");
    }

    @Test
    void aDocumentAndAChunkAreInsertedWithoutASelect() {
        KnowledgeDocument document = KnowledgeDocument.curated(
                "corpus/insert.md", DocumentType.REFERENCE, null, Fixtures.documentContent(Fixtures.sha('9')), Map.of(), Fixtures.NOW);
        deleteAfterTest(document);
        KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), 0, null, "text", 1, null, null, Map.of(), Fixtures.NOW);

        assertOnlyInserts(statementsDuring(() -> documents.save(document)), "knowledge_documents");
        assertOnlyInserts(statementsDuring(() -> chunks.save(chunk)), "knowledge_chunks");
    }

    @Test
    void readingBackAnEntityAndChangingItIssuesAnUpdateOfThatRowOnlyAfterTheSelect() {
        Scenario scenario = newScenario();
        RecordingStatementInspector.clear();

        inTransaction(() -> interviews.findById(scenario.interviewId()).orElseThrow().replaceMemory(Fixtures.memory(), Fixtures.NOW.plusSeconds(1)));

        List<String> statements = RecordingStatementInspector.statements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).startsWith("select ");
        assertThat(statements.get(1)).startsWith("update interviews set ").contains("version=?");
    }
}
