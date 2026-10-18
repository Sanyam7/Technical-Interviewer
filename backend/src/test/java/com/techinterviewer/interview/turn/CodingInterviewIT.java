package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.coding.repository.CodeTestRunRepository;
import com.techinterviewer.coding.support.ScriptedSandbox;
import com.techinterviewer.coding.support.ScriptedSandboxConfiguration;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The coding interview end to end through HTTP (AI_ARCHITECTURE.md section 2.11): a curated problem is asked, its examples
 * can be run, the submitted solution is tested before it is evaluated, the tests decide its correctness, and the transcript
 * explains the score. The sandbox is scripted (no Docker); {@code CodeSandboxIT} covers the real one.
 */
@Import(ScriptedSandboxConfiguration.class)
class CodingInterviewIT extends TurnApiTestBase {

    private static final String CODING_CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":30,
             "interviewType":"CODING","skills":["java"],"codingLanguage":"java"}""";

    @Autowired
    private ScriptedSandbox sandbox;

    @Autowired
    private CodeTestRunRepository runs;

    @BeforeEach
    void resetTheSandbox() {
        sandbox.reset();
    }

    /** A started CODING interview whose introduction is answered, so the coding problem is open. */
    private UUID atTheCodingProblem(UUID user) throws Exception {
        UUID interview = createInterview(user, CODING_CONFIGURATION);
        postAs(user, "/interviews/" + interview + "/start").andExpect(status().isOk());
        answerOk(user, interview, currentQuestionId(user, interview), "I build Java services.");
        return interview;
    }

    private JsonNode submitCode(UUID user, UUID interview, UUID question, String code) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("questionId", question.toString());
        body.put("content", code);
        body.put("codeLanguage", "java");
        return body(postAs(user, "/interviews/" + interview + "/answers", body.toString()).andExpect(status().isOk()));
    }

    private String run(String code) {
        ObjectNode body = json.createObjectNode();
        body.put("code", code);
        body.put("language", "java");
        return body.toString();
    }

    @Test
    void theCodingProblemComesFromTheCuratedBankWithRunnableExamplesAndStarterCode() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        int generated = generator.calls();

        JsonNode question = currentQuestion(user, interview);

        assertThat(question.get("kind").asString()).isEqualTo("CODING");
        assertThat(question.get("answerFormat").asString()).isEqualTo("CODE");
        JsonNode details = question.get("details");
        assertThat(details.get("title").asString()).isNotBlank();
        assertThat(details.get("testable").asBoolean()).isTrue();
        assertThat(details.get("starterCode").asString()).contains("class Solution");
        assertThat(details.get("examples").size()).isGreaterThan(0);
        assertThat(generator.calls()).as("no model call for a curated problem").isEqualTo(generated);
        InterviewQuestion stored = questions.findById(UUID.fromString(question.get("id").asString())).orElseThrow();
        assertThat(stored.getCodingProblemId()).isNotNull();
        assertThat(stored.getRubricId()).isEqualTo("coding.v2");
        assertThat(question.toString()).as("hidden tests and key points never reach the candidate")
                .doesNotContain("keyPoints").doesNotContain("PERFORMANCE");
    }

    @Test
    void runsTheVisibleExamplesOfADraftWithoutStoringOrEvaluatingAnything() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);
        int evaluations = evaluator.calls();

        JsonNode result = body(postAs(user, "/interviews/" + interview + "/questions/" + question + "/runs", run("class Solution {}"))
                .andExpect(status().isOk()));

        assertThat(result.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(result.get("passed").asInt()).isEqualTo(result.get("total").asInt()).isPositive();
        JsonNode first = result.get("results").get(0);
        assertThat(first.get("category").asString()).isEqualTo("EXAMPLE");
        assertThat(first.get("input").asString()).isNotBlank();
        assertThat(first.get("actual").asString()).isEqualTo(first.get("expected").asString());
        assertThat(result.get("output").asString()).isEqualTo("debug output from the candidate");
        assertThat(sandbox.jobs()).singleElement().satisfies(job -> assertThat(job.files().getFirst().name()).isEqualTo("Solution.java"));
        assertThat(runs.findAll()).isEmpty();
        assertThat(evaluator.calls()).isEqualTo(evaluations);
        assertThat(currentQuestionId(user, interview)).as("the interview did not move").isEqualTo(question);
    }

    @Test
    void reportsACompilationErrorOfADraft() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);

        postAs(user, "/interviews/" + interview + "/questions/" + question + "/runs", run("COMPILE_ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPILATION_ERROR"))
                .andExpect(jsonPath("$.compileOutput").value("Solution.java:1: error: ';' expected"))
                .andExpect(jsonPath("$.passed").value(0));
    }

    @Test
    void refusesARunInAnotherLanguageForAnotherQuestionOrWhenExecutionIsOff() throws Exception {
        UUID user = newUser();
        UUID interview = createInterview(user, CODING_CONFIGURATION);
        postAs(user, "/interviews/" + interview + "/start").andExpect(status().isOk());
        UUID intro = currentQuestionId(user, interview);

        postAs(user, "/interviews/" + interview + "/questions/" + intro + "/runs", run("class Solution {}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        answerOk(user, interview, intro, "I build Java services.");
        UUID coding = currentQuestionId(user, interview);
        postAs(user, "/interviews/" + interview + "/questions/" + intro + "/runs", run("class Solution {}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_NOT_CURRENT"));
        postAs(user, "/interviews/" + interview + "/questions/" + coding + "/runs", "{\"code\":\"x\",\"language\":\"python\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("language"));
        postAs(newUser(), "/interviews/" + interview + "/questions/" + coding + "/runs", run("class Solution {}"))
                .andExpect(status().isNotFound());

        sandbox.enabled(false);
        postAs(user, "/interviews/" + interview + "/questions/" + coding + "/runs", run("class Solution {}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CODE_EXECUTION_UNAVAILABLE"));
    }

    @Test
    void theSubmittedSolutionIsTestedOnceAndTheTestsDecideItsCorrectness() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);
        evaluator.quality(Quality.EXCELLENT);

        submitCode(user, interview, question, "class Solution { /* WRONG_EDGES */ }");

        UUID answerId = answers.findByQuestionIdAndInterviewId(question, interview).orElseThrow().getId();
        TestRunReport report = runs.findByAnswerId(answerId).orElseThrow().report();
        assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(report.passed(TestCategory.EDGE)).isZero();
        assertThat(report.functionalPassed()).isEqualTo(report.functionalTotal() - report.total(TestCategory.EDGE));
        assertThat(sandbox.jobs()).hasSize(1);

        EvaluationRequest request = evaluator.requests().stream().filter(r -> r.answerId().equals(answerId)).findFirst().orElseThrow();
        assertThat(request.evaluator()).isEqualTo(Evaluator.CODING);
        assertThat(request.codeTests()).isNotNull();
        assertThat(request.codeTests().ran()).isTrue();
        assertThat(request.codeTests().failures()).isNotEmpty().allMatch(line -> line.startsWith("EDGE test"));
        assertThat(request.codeTests().referenceTime()).isNotBlank();

        Evaluation evaluation = evaluations.findByAnswerId(answerId).orElseThrow();
        CriterionScore correctness = score(evaluation, Criterion.CORRECTNESS);
        assertThat(correctness.setBy()).isEqualTo("TEST_RESULTS");
        assertThat(correctness.modelRating()).isEqualTo(4);
        assertThat(correctness.rating()).isLessThan(4);
        assertThat(score(evaluation, Criterion.EDGE_CASES).rating()).isZero();
        assertThat(score(evaluation, Criterion.EDGE_CASES).cappedBy()).isEqualTo("EDGE_CASE_TESTS");
        assertThat(evaluation.getCodeAnalysis()).isNotNull();
    }

    @Test
    void withoutExecutionTheSolutionIsReviewedByTheAiAndItsCorrectnessIsCappedAsUnverified() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);
        assertThat(currentQuestion(user, interview).get("details").get("testable").asBoolean()).isTrue();
        sandbox.enabled(false);
        evaluator.quality(Quality.EXCELLENT);

        submitCode(user, interview, question, "class Solution { }");

        UUID answerId = answers.findByQuestionIdAndInterviewId(question, interview).orElseThrow().getId();
        assertThat(runs.findByAnswerId(answerId)).isEmpty();
        EvaluationRequest request = evaluator.requests().stream().filter(r -> r.answerId().equals(answerId)).findFirst().orElseThrow();
        assertThat(request.codeTests().ran()).isFalse();
        CriterionScore correctness = score(evaluations.findByAnswerId(answerId).orElseThrow(), Criterion.CORRECTNESS);
        assertThat(correctness.rating()).isEqualTo(3);
        assertThat(correctness.cappedBy()).isEqualTo("NO_TEST_EVIDENCE");
    }

    @Test
    void theTranscriptExplainsTheScoreOfASolutionAfterTheInterviewWithoutRevealingHiddenTests() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);
        submitCode(user, interview, question, "class Solution { /* SLOW */ }");
        assertThat(body(getAs(user, "/interviews/" + interview + "/transcript").andExpect(status().isOk())).toString())
                .as("no feedback while the interview runs").doesNotContain("codeTests\":{");

        finish(user, interview, "CANDIDATE_ENDED").andExpect(status().isOk());

        JsonNode transcript = body(getAs(user, "/interviews/" + interview + "/transcript").andExpect(status().isOk()));
        JsonNode coding = null;
        for (JsonNode entry : transcript.get("entries")) {
            if (entry.get("question").get("id").asString().equals(question.toString())) {
                coding = entry.get("feedback");
            }
        }
        assertThat(coding).isNotNull();
        UUID answerId = answers.findByQuestionIdAndInterviewId(question, interview).orElseThrow().getId();
        TestRunReport stored = runs.findByAnswerId(answerId).orElseThrow().report();
        int performance = stored.total(TestCategory.PERFORMANCE);
        JsonNode tests = coding.get("codeTests");
        assertThat(tests.get("language").asString()).isEqualTo("java");
        assertThat(tests.get("passed").asInt()).isEqualTo(tests.get("total").asInt() - performance);
        for (JsonNode result : tests.get("results")) {
            boolean example = result.get("category").asString().equals("EXAMPLE");
            assertThat(result.get("input").isNull()).as(result.get("name").asString()).isEqualTo(!example);
            assertThat(result.get("expected").isNull()).isEqualTo(!example);
        }
        JsonNode timeComplexity = criterion(coding, "TIME_COMPLEXITY");
        if (performance > 0) {
            assertThat(timeComplexity.get("basis").asString()).isEqualTo("CAPPED");
            assertThat(timeComplexity.get("note").asString()).contains("performance test");
        } else {
            assertThat(timeComplexity.get("basis").asString()).as("a problem without a performance test cannot cap it").isEqualTo("MODEL");
        }
        JsonNode correctness = criterion(coding, "CORRECTNESS");
        assertThat(correctness.get("basis").asString()).isEqualTo("TESTS");
        assertThat(correctness.get("note").asString()).startsWith("Set by the tests:");
        assertThat(criterion(coding, "READABILITY").get("basis").asString()).isEqualTo("MODEL");
        assertThat(criterion(coding, "READABILITY").get("note").isNull()).isTrue();
        assertThat(coding.get("codeAnalysis").get("timeComplexity").asString()).isEqualTo("O(n)");
        assertThat(coding.get("codeAnalysis").get("referenceTimeComplexity").asString()).isNotBlank();
    }

    @Test
    void aSandboxThatBreaksNeverFailsTheAnswerTheSolutionIsReviewedWithoutTests() throws Exception {
        UUID user = newUser();
        UUID interview = atTheCodingProblem(user);
        UUID question = currentQuestionId(user, interview);
        sandbox.breakNextRun();
        evaluator.quality(Quality.EXCELLENT);

        JsonNode response = submitCode(user, interview, question, "class Solution { }");

        assertThat(response.get("accepted").asBoolean()).isTrue();
        UUID answerId = answers.findByQuestionIdAndInterviewId(question, interview).orElseThrow().getId();
        assertThat(runs.findByAnswerId(answerId)).isEmpty();
        assertThat(score(evaluations.findByAnswerId(answerId).orElseThrow(), Criterion.CORRECTNESS).cappedBy()).isEqualTo("NO_TEST_EVIDENCE");
    }

    @Test
    void theCatalogSaysWhichLanguagesAreTested() throws Exception {
        getAs(newUser(), "/catalog/options").andExpect(status().isOk())
                .andExpect(jsonPath("$.codingLanguages[1]").value("javascript"))
                .andExpect(jsonPath("$.executableLanguages.length()").value(3))
                .andExpect(header().exists("ETag"));
    }

    private static CriterionScore score(Evaluation evaluation, Criterion criterion) {
        return evaluation.getCriterionScores().stream().filter(score -> score.criterion() == criterion).findFirst().orElseThrow();
    }

    private static JsonNode criterion(JsonNode feedback, String name) {
        for (JsonNode criterion : feedback.get("criteria")) {
            if (criterion.get("criterion").asString().equals(name)) {
                return criterion;
            }
        }
        throw new AssertionError("no criterion " + name);
    }
}
