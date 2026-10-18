package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.support.Assessments;
import com.techinterviewer.interview.domain.ArchitectureDiagram;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The system design interview end to end through HTTP (AI_ARCHITECTURE.md section 2.12): a curated problem with its facts, a design
 * answer with an architecture diagram, probes chosen by the engine for the weakest dimension and never repeated, the cumulative
 * evaluation with quoted evidence, the static fallback when no model can word a probe, and the transcript.
 */
class SystemDesignInterviewIT extends TurnApiTestBase {

    private static final String DESIGN_CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"SENIOR","difficulty":"MEDIUM","durationMinutes":45,
             "interviewType":"SYSTEM_DESIGN","skills":["java"]}""";

    /** A started SYSTEM_DESIGN interview whose introduction is answered, so the design problem is open. */
    private UUID atTheDesignProblem(UUID user) throws Exception {
        UUID interview = createInterview(user, DESIGN_CONFIGURATION);
        postAs(user, "/interviews/" + interview + "/start").andExpect(status().isOk());
        answerOk(user, interview, currentQuestionId(user, interview), "I design backend systems.");
        return interview;
    }

    private ObjectNode diagram() {
        ObjectNode diagram = json.createObjectNode();
        ArrayNode components = diagram.putArray("components");
        components.addObject().put("id", "api").put("kind", "SERVICE").put("name", "Redirect service").put("notes", "stateless, autoscaled");
        components.addObject().put("id", "db").put("kind", "DATABASE").put("name", "Links store");
        ArrayNode connections = diagram.putArray("connections");
        connections.addObject().put("from", "api").put("to", "db").put("label", "lookup by short code").put("async", false);
        return diagram;
    }

    private String designAnswer(UUID question, String content, ObjectNode diagram) {
        ObjectNode body = json.createObjectNode();
        body.put("questionId", question.toString());
        body.put("content", content);
        if (diagram != null) {
            body.set("architecture", diagram);
        }
        return body.toString();
    }

    /** Every design dimension at {@code rating} except those given, each resting on a quote. */
    private static AnswerAssessment design(EvaluationRequest request, int rating, Object... criteriaAndRatings) {
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Assessments.uniformRatings(Evaluator.SYSTEM_DESIGN, rating));
        for (int i = 0; i < criteriaAndRatings.length; i += 2) {
            Criterion criterion = (Criterion) criteriaAndRatings[i];
            int value = (Integer) criteriaAndRatings[i + 1];
            ratings.put(criterion, new CriterionRating(value, "because", value > 0 ? List.of("the candidate's words") : List.of()));
        }
        return Assessments.builder(Evaluator.SYSTEM_DESIGN).ratings(ratings).keyPointsCovered(request.expectedKeyPoints()).build();
    }

    @Test
    void theProblemComesFromTheBankWithItsFactsAndTheDiagramIsStoredAndEvaluated() throws Exception {
        UUID user = newUser();
        UUID interview = atTheDesignProblem(user);
        int generated = generator.calls();

        JsonNode question = currentQuestion(user, interview);

        assertThat(question.get("kind").asString()).isEqualTo("SYSTEM_DESIGN");
        assertThat(question.get("answerFormat").asString()).isEqualTo("TEXT");
        assertThat(question.get("design").get("title").asString()).startsWith("Design a");
        assertThat(question.get("design").get("facts").size()).isPositive();
        assertThat(question.get("design").get("probe").isNull()).isTrue();
        assertThat(question.toString()).as("key points never reach the candidate").doesNotContain("keyPoints", "kp1");
        assertThat(generator.calls()).as("no model call for a curated problem").isEqualTo(generated);
        UUID problemId = UUID.fromString(question.get("id").asString());
        InterviewQuestion stored = questions.findById(problemId).orElseThrow();
        assertThat(stored.getDesignProblemId()).isNotNull();
        assertThat(stored.getOrigin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(stored.getRubricId()).isEqualTo("system-design.v2");
        assertThat(stored.getExpectedKeyPoints()).hasSizeGreaterThanOrEqualTo(6);

        evaluator.scriptNext(request -> design(request, 3, Criterion.CACHING, 1, Criterion.SCALABILITY, 2));
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problemId, "A stateless redirect service reads the links store.", diagram()))
                .andExpect(status().isOk());

        UUID answerId = answers.findByQuestionIdAndInterviewId(problemId, interview).orElseThrow().getId();
        ArchitectureDiagram architecture = answers.findById(answerId).orElseThrow().getArchitecture();
        assertThat(architecture.components()).extracting(ArchitectureDiagram.Component::name).containsExactly("Redirect service", "Links store");
        EvaluationRequest request = evaluator.requests().stream().filter(r -> r.answerId().equals(answerId)).findFirst().orElseThrow();
        assertThat(request.evaluator()).isEqualTo(Evaluator.SYSTEM_DESIGN);
        assertThat(request.design().architecture()).contains("- Redirect service -> Links store: lookup by short code");
        assertThat(request.design().probe()).isNull();
        Evaluation evaluation = evaluations.findByAnswerId(answerId).orElseThrow();
        assertThat(evaluation.getCriterionScores()).hasSize(13);
        assertThat(score(evaluation, Criterion.CACHING).evidence()).containsExactly("the candidate's words");
    }

    @Test
    void theInterviewerProbesTheWeakestDimensionsOneAfterAnotherAndTheEvaluationStaysCumulative() throws Exception {
        UUID user = newUser();
        UUID interview = atTheDesignProblem(user);
        UUID problem = currentQuestionId(user, interview);
        evaluator.scriptNext(request -> design(request, 3, Criterion.CACHING, 1, Criterion.SCALABILITY, 2));
        clock.advance(Duration.ofMinutes(12));
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problem, "A stateless redirect service reads the links store.", diagram()))
                .andExpect(status().isOk());

        JsonNode first = currentQuestion(user, interview);
        assertThat(first.get("isFollowUp").asBoolean()).isTrue();
        assertThat(first.get("design").get("probe").asString()).as("0.12 x 2 beats 0.07 x 3").isEqualTo("SCALABILITY");
        assertThat(first.get("prompt").asString()).contains("PROBE_DIMENSION SCALABILITY");
        InterviewQuestion firstProbe = questions.findById(UUID.fromString(first.get("id").asString())).orElseThrow();
        InterviewQuestion root = questions.findById(problem).orElseThrow();
        assertThat(firstProbe.getProbeCriterion()).isEqualTo(Criterion.SCALABILITY);
        assertThat(firstProbe.getExpectedKeyPoints()).as("the thread is judged against the problem's key points")
                .isEqualTo(root.getExpectedKeyPoints());

        // The design still lacks on scalability, but that was probed: caching is next.
        evaluator.scriptNext(request -> design(request, 3, Criterion.CACHING, 1, Criterion.SCALABILITY, 2));
        clock.advance(Duration.ofMinutes(4));
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(firstProbe.getId(), "Add read replicas.", null)).andExpect(status().isOk());

        JsonNode second = currentQuestion(user, interview);
        assertThat(second.get("design").get("probe").asString()).isEqualTo("CACHING");
        InterviewQuestion secondProbe = questions.findById(UUID.fromString(second.get("id").asString())).orElseThrow();
        assertThat(secondProbe.getFollowUpDepth()).isEqualTo(2);
        UUID firstAnswer = answers.findByQuestionIdAndInterviewId(firstProbe.getId(), interview).orElseThrow().getId();
        EvaluationRequest probeEvaluation = evaluator.requests().stream().filter(r -> r.answerId().equals(firstAnswer)).findFirst().orElseThrow();
        assertThat(probeEvaluation.design().probe()).isEqualTo(Criterion.SCALABILITY);
        assertThat(probeEvaluation.design().architecture()).as("the latest diagram of the thread").contains("Redirect service");
        assertThat(probeEvaluation.thread()).hasSize(1);
        assertThat(probeEvaluation.thread().getFirst().questionPrompt()).isEqualTo(root.getPrompt());
        assertThat(generator.requests().getLast().followUp().designProbe().discussion()).hasSize(2);

        // Probes continue past the depth of two that other sections keep.
        evaluator.scriptNext(request -> design(request, 3, Criterion.CACHING, 3, Criterion.SCALABILITY, 2, Criterion.SECURITY, 1));
        clock.advance(Duration.ofMinutes(4));
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(secondProbe.getId(), "Cache hot codes in Redis.", null))
                .andExpect(status().isOk());
        JsonNode third = currentQuestion(user, interview);
        assertThat(third.get("design").get("probe").asString()).isEqualTo("SECURITY");
        assertThat(questions.findById(UUID.fromString(third.get("id").asString())).orElseThrow().getFollowUpDepth()).isEqualTo(3);
    }

    @Test
    void whenNoModelCanWordAProbeItsScenarioIsAskedAsItIs() throws Exception {
        UUID user = newUser();
        UUID interview = atTheDesignProblem(user);
        UUID problem = currentQuestionId(user, interview);
        evaluator.scriptNext(request -> design(request, 3, Criterion.FAILURE_HANDLING, 0));
        generator.unavailable();

        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problem, "One service and a database.", null)).andExpect(status().isOk());

        JsonNode probe = currentQuestion(user, interview);
        assertThat(probe.get("design").get("probe").asString()).isEqualTo("FAILURE_HANDLING");
        InterviewQuestion stored = questions.findById(UUID.fromString(probe.get("id").asString())).orElseThrow();
        assertThat(stored.getOrigin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(probe.get("prompt").asString()).endsWith("?");
        assertThat(stored.getExpectedKeyPoints()).isEqualTo(questions.findById(problem).orElseThrow().getExpectedKeyPoints());
    }

    @Test
    void aDiagramIsRefusedOutsideTheDesignSectionOrWhenItsShapeIsWrong() throws Exception {
        UUID user = newUser();
        UUID interview = createInterview(user, DESIGN_CONFIGURATION);
        postAs(user, "/interviews/" + interview + "/start").andExpect(status().isOk());
        UUID intro = currentQuestionId(user, interview);
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(intro, "I design systems.", diagram()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("architecture"));
        answerOk(user, interview, intro, "I design systems.");
        UUID problem = currentQuestionId(user, interview);

        ObjectNode dangling = diagram();
        ((ArrayNode) dangling.get("connections")).addObject().put("from", "api").put("to", "cache").put("async", true);
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problem, "A design.", dangling))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("architecture"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("does not exist")));
        ObjectNode badKind = diagram();
        ((ObjectNode) badKind.get("components").get(0)).put("kind", "MAINFRAME");
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problem, "A design.", badKind)).andExpect(status().isBadRequest());
        assertThat(answers.findByQuestionIdAndInterviewId(problem, interview)).as("nothing was stored").isEmpty();
    }

    @Test
    void theTranscriptShowsTheDiagramTheProbesAndAfterTheInterviewTheQuotedEvidence() throws Exception {
        UUID user = newUser();
        UUID interview = atTheDesignProblem(user);
        UUID problem = currentQuestionId(user, interview);
        evaluator.scriptNext(request -> design(request, 3, Criterion.OBSERVABILITY, 0));
        postAs(user, "/interviews/" + interview + "/answers", designAnswer(problem, "A stateless redirect service reads the links store.", diagram()))
                .andExpect(status().isOk());
        UUID probe = currentQuestionId(user, interview);

        finish(user, interview, "CANDIDATE_ENDED").andExpect(status().isOk());

        JsonNode transcript = body(getAs(user, "/interviews/" + interview + "/transcript").andExpect(status().isOk()));
        JsonNode problemEntry = entry(transcript, problem);
        assertThat(problemEntry.get("answer").get("architecture").get("components").size()).isEqualTo(2);
        assertThat(entry(transcript, probe).get("question").get("design").get("probe").asString()).isEqualTo("OBSERVABILITY");
        JsonNode feedback = problemEntry.get("feedback");
        assertThat(feedback.get("criteria").size()).isEqualTo(13);
        for (JsonNode criterion : feedback.get("criteria")) {
            boolean zero = criterion.get("rating").asInt() == 0;
            assertThat(criterion.get("evidence").size()).as(criterion.get("criterion").asString()).isEqualTo(zero ? 0 : 1);
        }
    }

    private static JsonNode entry(JsonNode transcript, UUID question) {
        for (JsonNode entry : transcript.get("entries")) {
            if (entry.get("question").get("id").asString().equals(question.toString())) {
                return entry;
            }
        }
        throw new AssertionError("no entry for " + question);
    }

    private static CriterionScore score(Evaluation evaluation, Criterion criterion) {
        return evaluation.getCriterionScores().stream().filter(score -> score.criterion() == criterion).findFirst().orElseThrow();
    }
}
