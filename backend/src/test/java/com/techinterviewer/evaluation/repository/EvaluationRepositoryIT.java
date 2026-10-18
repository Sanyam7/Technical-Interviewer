package com.techinterviewer.evaluation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFindings;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.ModelProvenance;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EvaluationRepositoryIT extends PersistenceTestBase {

    private Evaluation load(Evaluation evaluation) {
        return inTransaction(() -> evaluations.findById(evaluation.getId()).orElseThrow());
    }

    private CandidateAnswer answerAt(Scenario scenario, int sequenceNo, Instant submittedAt) {
        InterviewQuestion question = saveQuestion(scenario, sequenceNo);
        CandidateAnswer answer = CandidateAnswer.submit(question.getId(), scenario.interviewId(), "answer " + sequenceNo, null, null, submittedAt);
        inTransaction(() -> answers.saveAndFlush(answer));
        return answer;
    }

    @Test
    void anEvaluationKeepsEveryFieldIncludingTheJsonDocumentsTheFlagArrayAndTheTwoDecimalScore() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation original = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(original));

        Evaluation loaded = load(original);

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getAnswerId()).isEqualTo(answer.getId());
        assertThat(loaded.getEvaluator()).isEqualTo(Evaluator.TECHNICAL);
        assertThat(loaded.getOverallScore()).isEqualByComparingTo("57.50").hasScaleOf(2);
        assertThat(loaded.getCriterionScores()).isEqualTo(Fixtures.findings().criterionScores());
        assertThat(loaded.getKeyPoints()).isEqualTo(Fixtures.findings().keyPoints());
        assertThat(loaded.getFactualErrors()).isEqualTo(Fixtures.findings().factualErrors());
        assertThat(loaded.getCandidateClaims()).containsExactly("Migrated a monolith to Kafka");
        assertThat(loaded.getSummary()).isEqualTo("Solid on visibility, missed mutual exclusion.");
        assertThat(loaded.getFlags()).containsExactlyInAnyOrder(EvaluationFlag.OFF_TOPIC, EvaluationFlag.INJECTION_SUSPECTED);
        assertThat(loaded.getPromptId()).isEqualTo("evaluate-technical");
        assertThat(loaded.getPromptVersion()).isEqualTo("1.2");
        assertThat(loaded.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(loaded.getLlmCallId()).isEqualTo(Fixtures.EVALUATION_LLM_CALL);
        assertThat(loaded.getCreatedAt()).isEqualTo(Fixtures.NOW);
    }

    @Test
    void theFlagsAreAPostgresTextArrayAndTheOtherDocumentsAreJsonb() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation original = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(original));

        String joined = jdbc.queryForObject("select array_to_string(flags, ',') from evaluations where id = ?", String.class, original.getId());
        assertThat(joined.split(",")).containsExactlyInAnyOrder("OFF_TOPIC", "INJECTION_SUSPECTED");
        assertThat(jdbc.queryForObject("select flags @> array['OFF_TOPIC'] from evaluations where id = ?", Boolean.class, original.getId())).isTrue();
        assertThat(jdbc.queryForObject("select criterion_scores -> 0 ->> 'criterion' from evaluations where id = ?", String.class, original.getId()))
                .isEqualTo("CORRECTNESS");
        assertThat(jdbc.queryForObject("select candidate_claims ->> 0 from evaluations where id = ?", String.class, original.getId()))
                .isEqualTo("Migrated a monolith to Kafka");
        assertThat(jdbc.queryForObject("select overall_score from evaluations where id = ?", BigDecimal.class, original.getId()))
                .isEqualByComparingTo("57.50");
    }

    @Test
    void anEvaluationWithoutFlagsFactualErrorsClaimsOrKeyPointsRoundTripsAsEmptyCollections() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        EvaluationFindings plain = new EvaluationFindings(
                List.of(new CriterionScore(Criterion.CORRECTNESS, 4, 1.0, "right")), List.of(), List.of(), List.of(), "Fine.", Set.of());
        Evaluation original = Evaluation.create(
                answer.getId(), Evaluator.INTRO, new BigDecimal("100"), plain, new ModelProvenance("p", "1", "m", null), Fixtures.NOW);
        inTransaction(() -> evaluations.saveAndFlush(original));

        Evaluation loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getFlags()).isEmpty();
        assertThat(loaded.getFactualErrors()).isEmpty();
        assertThat(loaded.getCandidateClaims()).isEmpty();
        assertThat(loaded.getKeyPoints()).isEmpty();
        assertThat(loaded.getLlmCallId()).isNull();
        assertThat(loaded.getOverallScore()).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select cardinality(flags) from evaluations where id = ?", Integer.class, original.getId())).isZero();
    }

    @Test
    void everyFlagValueSurvivesTheArrayColumn() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        EvaluationFindings findings = new EvaluationFindings(
                List.of(new CriterionScore(Criterion.CORRECTNESS, 2, 1.0, "")), List.of(), List.of(), List.of(), "All flags.",
                EnumSet.allOf(EvaluationFlag.class));
        Evaluation original = Evaluation.create(
                answer.getId(), Evaluator.TECHNICAL, new BigDecimal("50"), findings, Fixtures.provenance(), Fixtures.NOW);
        inTransaction(() -> evaluations.saveAndFlush(original));

        assertThat(load(original).getFlags()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(EvaluationFlag.class));
    }

    @Test
    void theScoreIsRoundedHalfUpToTwoDecimalsBeforeItIsStored() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation original = Evaluation.create(
                answer.getId(), Evaluator.TECHNICAL, new BigDecimal("33.335"), Fixtures.findings(), Fixtures.provenance(), Fixtures.NOW);
        inTransaction(() -> evaluations.saveAndFlush(original));

        assertThat(load(original).getOverallScore()).isEqualTo(new BigDecimal("33.34"));
    }

    @Test
    void anAnswerHasAtMostOneEvaluation() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        inTransaction(() -> evaluations.saveAndFlush(Fixtures.evaluation(answer.getId())));

        assertThatThrownBy(() -> inTransaction(() -> evaluations.saveAndFlush(Fixtures.evaluation(answer.getId()))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("evaluations", "answer_id", answer.getId())).isOne();
    }

    @Test
    void anEvaluationNeedsARealAnswerAndARecordedModelCall() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation withoutAnswer = Fixtures.evaluation(UuidV7.generate());
        Evaluation unknownCall = Evaluation.create(
                answer.getId(), Evaluator.TECHNICAL, new BigDecimal("10"), Fixtures.findings(),
                new ModelProvenance("p", "1", "m", UuidV7.generate()), Fixtures.NOW);

        assertThatThrownBy(() -> inTransaction(() -> evaluations.saveAndFlush(withoutAnswer))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> evaluations.saveAndFlush(unknownCall))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsAScoreOutsideZeroToHundredAndAnUnknownEvaluator() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        String insert = "insert into evaluations (id, answer_id, evaluator, overall_score, criterion_scores, key_points, summary, prompt_id, prompt_version, model)"
                + " values (?, ?, ?, ?, '[]', '[]', 's', 'p', '1', 'm')";

        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), answer.getId(), "TECHNICAL", new BigDecimal("100.01")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), answer.getId(), "TECHNICAL", new BigDecimal("-0.01")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UuidV7.generate(), answer.getId(), "ORACLE", new BigDecimal("10")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEvaluationIsFoundByItsAnswerAndExistenceIsReported() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        CandidateAnswer unevaluated = saveAnswer(scenario, saveQuestion(scenario, 2));
        Evaluation evaluation = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(evaluation));

        assertThat(evaluations.findByAnswerId(answer.getId())).get().extracting(Evaluation::getId).isEqualTo(evaluation.getId());
        assertThat(evaluations.findByAnswerId(unevaluated.getId())).isEmpty();
        assertThat(evaluations.existsByAnswerId(answer.getId())).isTrue();
        assertThat(evaluations.existsByAnswerId(unevaluated.getId())).isFalse();
        assertThat(evaluations.existsByAnswerId(UUID.randomUUID())).isFalse();
    }

    @Test
    void theEvaluationsOfAnInterviewFollowTheSubmissionOrderOfItsAnswersAndNeverIncludeAnotherInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        CandidateAnswer late = answerAt(scenario, 1, Fixtures.NOW.plusSeconds(30));
        CandidateAnswer early = answerAt(scenario, 2, Fixtures.NOW.plusSeconds(10));
        answerAt(scenario, 3, Fixtures.NOW.plusSeconds(20));
        CandidateAnswer foreign = answerAt(other, 1, Fixtures.NOW);
        Evaluation lateEvaluation = Fixtures.evaluation(late.getId());
        Evaluation earlyEvaluation = Fixtures.evaluation(early.getId());
        Evaluation foreignEvaluation = Fixtures.evaluation(foreign.getId());
        inTransaction(() -> {
            evaluations.saveAndFlush(lateEvaluation);
            evaluations.saveAndFlush(earlyEvaluation);
            evaluations.saveAndFlush(foreignEvaluation);
        });

        assertThat(evaluations.findByInterviewId(scenario.interviewId()))
                .extracting(Evaluation::getId).containsExactly(earlyEvaluation.getId(), lateEvaluation.getId());
        assertThat(evaluations.findByInterviewId(other.interviewId())).extracting(Evaluation::getId).containsExactly(foreignEvaluation.getId());
        assertThat(evaluations.findByInterviewId(UuidV7.generate())).isEmpty();
    }

    @Test
    void theEvaluationDisappearsWithItsAnswer() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation evaluation = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(evaluation));

        jdbc.update("delete from answers where id = ?", answer.getId());

        assertThat(count("evaluations", "id", evaluation.getId())).isZero();
    }

    @Test
    void deletingTheRecordedModelCallClearsTheLinkAndKeepsTheEvaluation() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation evaluation = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(evaluation));

        jdbc.update("delete from llm_calls where id = ?", Fixtures.EVALUATION_LLM_CALL);

        assertThat(load(evaluation).getLlmCallId()).isNull();
    }

    @Test
    void theCollectionsOfAnEvaluationReadFromTheDatabaseCannotBeModifiedThroughTheGetters() {
        Scenario scenario = newScenario();
        CandidateAnswer answer = saveAnswer(scenario, saveQuestion(scenario, 1));
        Evaluation original = Fixtures.evaluation(answer.getId());
        inTransaction(() -> evaluations.saveAndFlush(original));

        Evaluation loaded = load(original);

        assertThatThrownBy(() -> loaded.getCriterionScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.getKeyPoints().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.getFactualErrors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.getCandidateClaims().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.getFlags().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
