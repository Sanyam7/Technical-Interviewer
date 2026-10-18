package com.techinterviewer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.report.InterviewReport;
import com.techinterviewer.evaluation.report.ReportSkillScore;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionGrounding;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What the foreign keys do to a whole interview aggregate, driven through rows written by the entities: everything that
 * belongs to an interview goes with it, the things it merely refers to (configuration, knowledge, the model call log)
 * stay, and a configuration cannot be pulled out from under an interview.
 */
class InterviewAggregateCascadeIT extends PersistenceTestBase {

    private record Graph(
            Scenario scenario,
            InterviewQuestion question,
            CandidateAnswer answer,
            Evaluation evaluation,
            InterviewReport report,
            KnowledgeChunk chunk) {

        UUID interviewId() {
            return scenario.interviewId();
        }
    }

    private Graph fullInterview(char documentSeed) {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer answer = saveAnswer(scenario, question);
        Evaluation evaluation = Fixtures.evaluation(answer.getId());
        InterviewReport report = InterviewReport.pending(scenario.interviewId(), Fixtures.NOW);
        KnowledgeChunk chunk = saveChunkOfPublicDocument(documentSeed);
        inTransaction(() -> {
            evaluations.saveAndFlush(evaluation);
            reports.saveAndFlush(report);
            skillScores.saveAndFlush(new ReportSkillScore(
                    report.getId(), skillIds(1).getFirst(), new BigDecimal("50"), Confidence.LOW, 1, BigDecimal.ONE));
            groundings.saveAndFlush(new QuestionGrounding(question.getId(), chunk.getId(), 1, 0.5f));
        });
        jdbc.update("insert into llm_calls (id, interview_id, user_id, task, provider, model, status, latency_ms) values (?, ?, ?, 'TEST', 'test', 'test', 'OK', 1)",
                UuidV7.generate(), scenario.interviewId(), scenario.userId());
        return new Graph(scenario, question, answer, evaluation, report, chunk);
    }

    private void assertEverythingOfTheInterviewIsThere(Graph graph) {
        assertThat(count("questions", "interview_id", graph.interviewId())).isOne();
        assertThat(count("answers", "interview_id", graph.interviewId())).isOne();
        assertThat(count("evaluations", "id", graph.evaluation().getId())).isOne();
        assertThat(count("reports", "interview_id", graph.interviewId())).isOne();
        assertThat(count("report_skill_scores", "report_id", graph.report().getId())).isOne();
        assertThat(count("interview_state_transitions", "interview_id", graph.interviewId())).isOne();
        assertThat(count("question_sources", "question_id", graph.question().getId())).isOne();
    }

    @Test
    void deletingTheInterviewRowRemovesEverythingThatBelongsToItAndNothingElse() {
        Graph graph = fullInterview('a');
        assertEverythingOfTheInterviewIsThere(graph);

        jdbc.update("delete from interviews where id = ?", graph.interviewId());

        assertThat(count("questions", "interview_id", graph.interviewId())).isZero();
        assertThat(count("answers", "interview_id", graph.interviewId())).isZero();
        assertThat(count("evaluations", "id", graph.evaluation().getId())).isZero();
        assertThat(count("reports", "interview_id", graph.interviewId())).isZero();
        assertThat(count("report_skill_scores", "report_id", graph.report().getId())).isZero();
        assertThat(count("interview_state_transitions", "interview_id", graph.interviewId())).isZero();
        assertThat(count("question_sources", "question_id", graph.question().getId())).isZero();
        assertThat(count("knowledge_chunks", "id", graph.chunk().getId())).as("knowledge outlives the interview").isOne();
        assertThat(count("interview_configurations", "id", graph.scenario().configuration().getId())).as("the configuration is reusable").isOne();
        assertThat(count("users", "id", graph.scenario().userId())).isOne();
        assertThat(jdbc.queryForObject("select interview_id is null from llm_calls where user_id = ?", Boolean.class, graph.scenario().userId()))
                .as("the model call log is kept for metrics").isTrue();
    }

    @Test
    void deletingOneInterviewLeavesAnotherInterviewOfTheSameCandidateUntouched() {
        Graph doomed = fullInterview('a');
        Graph survivor = fullInterview('b');

        jdbc.update("delete from interviews where id = ?", doomed.interviewId());

        assertEverythingOfTheInterviewIsThere(survivor);
    }

    @Test
    void deletingTheCandidateRemovesTheirInterviewsConfigurationsAndEverythingBelow() {
        Graph graph = fullInterview('a');

        jdbc.update("delete from users where id = ?", graph.scenario().userId());

        assertThat(count("interviews", "id", graph.interviewId())).isZero();
        assertThat(count("interview_configurations", "id", graph.scenario().configuration().getId())).isZero();
        assertThat(count("interview_configuration_skills", "configuration_id", graph.scenario().configuration().getId())).isZero();
        assertThat(count("questions", "interview_id", graph.interviewId())).isZero();
        assertThat(count("answers", "interview_id", graph.interviewId())).isZero();
        assertThat(count("evaluations", "id", graph.evaluation().getId())).isZero();
        assertThat(count("reports", "interview_id", graph.interviewId())).isZero();
        assertThat(count("report_skill_scores", "report_id", graph.report().getId())).isZero();
        assertThat(count("interview_state_transitions", "interview_id", graph.interviewId())).isZero();
        assertThat(count("question_sources", "question_id", graph.question().getId())).isZero();
        assertThat(count("knowledge_chunks", "id", graph.chunk().getId())).isOne();
    }

    @Test
    void deletingTheQuestionRemovesItsAnswerItsEvaluationAndItsSourcesButNotTheInterview() {
        Graph graph = fullInterview('a');

        jdbc.update("delete from questions where id = ?", graph.question().getId());

        assertThat(count("answers", "id", graph.answer().getId())).isZero();
        assertThat(count("evaluations", "id", graph.evaluation().getId())).isZero();
        assertThat(count("question_sources", "question_id", graph.question().getId())).isZero();
        assertThat(count("interviews", "id", graph.interviewId())).isOne();
        assertThat(count("reports", "interview_id", graph.interviewId())).isOne();
    }

    @Test
    void aConfigurationUsedByAnInterviewCannotBeDeletedEvenWithPlainSql() {
        Graph graph = fullInterview('a');

        assertThatThrownBy(() -> jdbc.update("delete from interview_configurations where id = ?", graph.scenario().configuration().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("interview_configurations", "id", graph.scenario().configuration().getId())).isOne();
        assertThat(count("interviews", "id", graph.interviewId())).isOne();
    }

    @Test
    void anUnusedConfigurationCanBeDeletedOnceItsInterviewIsGone() {
        Graph graph = fullInterview('a');
        jdbc.update("delete from interviews where id = ?", graph.interviewId());

        assertThat(jdbc.update("delete from interview_configurations where id = ?", graph.scenario().configuration().getId())).isOne();
    }
}
