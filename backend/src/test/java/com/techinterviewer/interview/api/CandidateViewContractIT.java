package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.techinterviewer.interview.ForbiddenFields;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.turn.TurnApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The contract of the candidate-facing JSON: no response of any endpoint of the interview API, successful or not,
 * contains a property that only the server may know, and no score appears before the interview is COMPLETED. Every
 * response of every endpoint is read as a tree and every property name in it is checked (see {@link ForbiddenFields}).
 * The test also fails when an endpoint of the three controllers (interviews, configurations and turns) was not walked,
 * so a new endpoint cannot slip past it.
 *
 * <p>The data is rich on purpose: key points, rubrics, coding material, a follow-up, evaluations and memory exist in
 * the database, so a response that leaked them would show them.
 */
class CandidateViewContractIT extends TurnApiTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final Set<String> walked = new TreeSet<>();
    private int responses;

    private void walk(UUID user, HttpMethod method, String pattern, String path, String body, boolean feedbackAllowed) throws Exception {
        MockHttpServletRequestBuilder request = switch (method.name()) {
            case "GET" -> get(API + path);
            case "POST" -> post(API + path);
            case "DELETE" -> delete(API + path);
            default -> throw new IllegalArgumentException(method.name());
        };
        request.with(as(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        walked.add(method + " " + API + pattern);
        String text = result.getResponse().getContentAsString();
        if (text.isEmpty()) {
            return;
        }
        JsonNode tree = json.readTree(text);
        responses++;
        assertThat(ForbiddenFields.find(tree, feedbackAllowed))
                .as("%s %s answered %d with %s", method, path, result.getResponse().getStatus(), text)
                .isEmpty();
    }

    private void walk(UUID user, String pattern, String path) throws Exception {
        walk(user, HttpMethod.GET, pattern, path, null, false);
    }

    @Test
    void noResponseOfAnyEndpointShowsWhatOnlyTheServerMayKnow() throws Exception {
        UUID ada = newUser();
        UUID bob = newUser();

        // Configurations: create (valid and invalid), list, read, delete (in use and free), foreign.
        String configurations = "/interview-configurations";
        walk(ada, HttpMethod.POST, configurations, configurations, FULL_LOOP_CONFIGURATION, false);
        walk(ada, HttpMethod.POST, configurations, configurations, with(FULL_LOOP_CONFIGURATION, "durationMinutes", 7), false);
        UUID spare = saveConfiguration(ada, TECHNICAL_CONFIGURATION);
        walk(ada, configurations, configurations);
        walk(ada, configurations + "/{id}", configurations + "/" + spare);
        walk(bob, configurations + "/{id}", configurations + "/" + spare);
        walk(ada, HttpMethod.DELETE, configurations + "/{id}", configurations + "/" + spare, null, false);

        // Interviews: create, refused create, list.
        walk(ada, HttpMethod.POST, "/interviews", "/interviews", "{\"configuration\":" + FULL_LOOP_CONFIGURATION + "}", false);
        walk(ada, HttpMethod.POST, "/interviews", "/interviews", "{}", false);
        UUID usedConfiguration = saveConfiguration(ada, FULL_LOOP_CONFIGURATION);
        UUID id = UUID.fromString(body(postAs(ada, "/interviews", "{\"configurationId\":\"" + usedConfiguration + "\"}")).get("id").asString());
        walk(ada, HttpMethod.DELETE, configurations + "/{id}", configurations + "/" + usedConfiguration, null, false);
        walk(ada, "/interviews", "/interviews");
        walk(ada, "/interviews", "/interviews?state=INIT");

        // Before the start.
        walk(ada, "/interviews/{id}", "/interviews/" + id);
        walk(ada, "/interviews/{id}/state", "/interviews/" + id + "/state");
        walk(ada, "/interviews/{id}/questions", "/interviews/" + id + "/questions");
        walk(ada, "/interviews/{id}/questions/current", "/interviews/" + id + "/questions/current");
        walk(ada, "/interviews/{id}/transcript", "/interviews/" + id + "/transcript");

        // Started: the introduction question.
        walk(ada, HttpMethod.POST, "/interviews/{id}/start", "/interviews/" + id + "/start", null, false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/start", "/interviews/" + id + "/start", null, false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/abandon", "/interviews/" + id + "/abandon", null, false);
        UUID abandonable = createInterview(ada);
        walk(ada, HttpMethod.POST, "/interviews/{id}/abandon", "/interviews/" + abandonable + "/abandon", null, false);

        // In the middle: technical, follow-up and coding questions with answers and stored evaluations.
        InterviewQuestion intro = questions.findByInterviewIdOrderBySequenceNoAsc(id).getFirst();
        fixture.answer(intro, "I build backends.", clock.instant());
        fixture.move(id, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, clock.instant());
        InterviewQuestion technical = fixture.askTechnical(id, "java", "concurrency", clock.instant());
        CandidateAnswer answer = fixture.answer(technical, "volatile gives visibility.", clock.instant());
        fixture.evaluate(answer, clock.instant());
        InterviewQuestion followUp = fixture.askFollowUp(id, technical, Section.QUESTIONING, clock.instant());
        fixture.answer(followUp, "synchronized locks", clock.instant());
        fixture.move(id, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, clock.instant());
        InterviewQuestion coding = fixture.askCoding(id, clock.instant());
        walkReads(ada, id, coding.getId(), false);
        walk(ada, "/interviews/{id}/questions/{questionId}", "/interviews/" + id + "/questions/" + technical.getId());
        walk(ada, "/interviews/{id}/questions/{questionId}", "/interviews/" + id + "/questions/" + followUp.getId());

        // Over, evaluations stored but the interview is not completed.
        fixture.move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, clock.instant());
        walkReads(ada, id, coding.getId(), false);

        // Completed: the only moment feedback may appear.
        fixture.move(id, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, clock.instant());
        walk(ada, HttpMethod.GET, "/interviews/{id}/transcript", "/interviews/" + id + "/transcript", null, true);
        String transcript = getAs(ada, "/interviews/" + id + "/transcript").andReturn().getResponse().getContentAsString();
        assertThat(transcript).contains("\"overallScore\"").contains("\"criteria\"").contains("\"keyPoints\"");
        walkReads(ada, id, coding.getId(), true);

        // Somebody else's interview.
        walkReads(bob, id, coding.getId(), false);

        // The turn, through the real endpoints and the scripted ports: every answer, skip, retry, refusal, recovery and finish.
        walkTheTurn(ada, bob);

        assertThat(responses).isGreaterThan(40);
        assertThat(walked).containsAll(endpointsOfTheInterviewControllers());
    }

    /** Plays interviews through the turn endpoints, checking every response (the successful ones and the refusals). */
    private void walkTheTurn(UUID ada, UUID bob) throws Exception {
        UUID id = createInterview(ada, FULL_LOOP_CONFIGURATION);
        String base = "/interviews/" + id;
        walk(ada, HttpMethod.POST, "/interviews/{id}/start", base + "/start", null, false);
        UUID intro = currentQuestionId(ada, id);
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", "{\"questionId\":\"" + intro + "\"}", false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(intro, "I build backends.").replace("}", ",\"score\":100}"), false);
        evaluator.flagNextAsInjection(com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality.PARTIAL);
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(intro, "I build backends."), false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(intro, "I build backends."), false);
        walk(bob, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(intro, "not mine"), false);
        UUID root = currentQuestionId(ada, id);
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(intro, "again"), false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/{questionId}/skip", base + "/questions/" + root + "/skip", null, false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/{questionId}/skip", base + "/questions/" + root + "/skip", "{\"timeTakenSeconds\":3}", false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/next", base + "/questions/next", null, false);
        generator.unavailable();
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", base + "/answers", answerBody(currentQuestionId(ada, id), "a partial answer"), false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/next", base + "/questions/next", null, false);
        generator.available();
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/next", base + "/questions/next", null, false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/questions/next", base + "/questions/next", null, false);
        walk(bob, HttpMethod.POST, "/interviews/{id}/questions/next", base + "/questions/next", null, false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/finish", base + "/finish", "{\"reason\":\"ACKNOWLEDGE_COMPLETE\"}", false);
        evaluator.unavailable();
        UUID late = createInterview(ada);
        postAs(ada, "/interviews/" + late + "/start");
        walk(ada, HttpMethod.POST, "/interviews/{id}/answers", "/interviews/" + late + "/answers", answerBody(currentQuestionId(ada, late), "an answer"), false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/finish", "/interviews/" + late + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}", false);
        walk(ada, HttpMethod.POST, "/interviews/{id}/finish", "/interviews/" + late + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}", false);
        evaluator.available();
        walk(ada, HttpMethod.POST, "/interviews/{id}/finish", "/interviews/" + late + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}", false);
        walk(bob, HttpMethod.POST, "/interviews/{id}/finish", "/interviews/" + late + "/finish", "{\"reason\":\"CANDIDATE_ENDED\"}", false);

        // A whole interview, so that every kind of question (technical, follow-up, coding, design) passes through a response.
        evaluator.quality(com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality.PARTIAL);
        UUID full = startedInterview(ada, FULL_LOOP_CONFIGURATION);
        for (Turn turn : play(ada, full, java.time.Duration.ofSeconds(5), question -> "an answer")) {
            assertThat(ForbiddenFields.find(turn.response(), false)).as("answer to question %s", turn.question().get("id")).isEmpty();
            responses++;
        }
        walk(ada, HttpMethod.POST, "/interviews/{id}/finish", "/interviews/" + full + "/finish", "{\"reason\":\"ACKNOWLEDGE_COMPLETE\"}", false);
        walk(ada, HttpMethod.GET, "/interviews/{id}/transcript", "/interviews/" + full + "/transcript", null, true);
    }

    /** Every read endpoint of the interview, as the owner sees it in the interview's current state. */
    private void walkReads(UUID user, UUID id, UUID questionId, boolean transcriptMayCarryFeedback) throws Exception {
        walk(user, "/interviews", "/interviews");
        walk(user, "/interviews/{id}", "/interviews/" + id);
        walk(user, "/interviews/{id}/state", "/interviews/" + id + "/state");
        walk(user, "/interviews/{id}/questions", "/interviews/" + id + "/questions");
        walk(user, "/interviews/{id}/questions/current", "/interviews/" + id + "/questions/current");
        walk(user, "/interviews/{id}/questions/{questionId}", "/interviews/" + id + "/questions/" + questionId);
        walk(user, HttpMethod.GET, "/interviews/{id}/transcript", "/interviews/" + id + "/transcript", null, transcriptMayCarryFeedback);
    }

    private Set<String> endpointsOfTheInterviewControllers() {
        return mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> isOneOfTheInterviewControllers(entry.getValue()))
                .flatMap(entry -> endpoints(entry.getKey()).stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isOneOfTheInterviewControllers(HandlerMethod handler) {
        Class<?> type = handler.getBeanType();
        return type == InterviewController.class || type == InterviewConfigurationController.class || type == TurnController.class;
    }

    private static List<String> endpoints(RequestMappingInfo info) {
        List<String> endpoints = new ArrayList<>();
        for (var method : info.getMethodsCondition().getMethods()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                endpoints.add(method + " " + pattern);
            }
        }
        return endpoints;
    }
}
