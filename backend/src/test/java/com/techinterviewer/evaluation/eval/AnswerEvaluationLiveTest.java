package com.techinterviewer.evaluation.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.provider.ollama.OllamaLlmProvider;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.evaluation.AnswerScorer;
import com.techinterviewer.evaluation.RatingRules;
import com.techinterviewer.evaluation.ScoringPolicy;
import com.techinterviewer.evaluation.ShippedScoring;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the real answer evaluator on every case of {@code ai-evals/datasets/answer-evaluation.jsonl}, scores its output
 * exactly as the application does (evidence verification in the agent, then {@link RatingRules}, the weights of the
 * case's skill profile from application.yml and the confidence policy), and compares the result with what the dataset
 * expects. Writes a scorecard to {@code ai-evals/reports/answer-evaluation-<provider>-<model>.md} and every raw result
 * to the {@code .jsonl} next to it. Opt-in (tag {@code live}): it needs Ollama with the model pulled, or a Gemini key.
 *
 * <pre>
 * ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=AnswerEvaluationLiveTest
 * LIVE_EVAL_PROVIDER=gemini GEMINI_API_KEY=... (same command)    Gemini, model LIVE_EVAL_MODEL or GEMINI_MODEL
 * LIVE_EVAL_REPEATS=2                                         every case twice, for the consistency section
 * LIVE_EVAL_CASES=java-.*                                     only the matching case ids
 * LIVE_EVAL_ENFORCE=true                                      fail unless every pass criterion holds
 * </pre>
 *
 * The evaluator runs without reference material (no knowledge base: this measures the model and the prompt, not the
 * retrieval). By default the test only fails when an evaluation cannot run at all; the pass criteria are reported, and
 * enforced with {@code LIVE_EVAL_ENFORCE}, because a small local model is expected to miss some of them.
 */
@Tag("live")
class AnswerEvaluationLiveTest {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationLiveTest.class);
    private static final String PROVIDER = env("LIVE_EVAL_PROVIDER", "ollama");
    private static final String OLLAMA = env("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = env("LIVE_EVAL_MODEL", PROVIDER.equals("gemini")
            ? env("GEMINI_MODEL", "gemini-3.8-flash") : env("OLLAMA_MODEL", "qwen3.5:4b"));
    private static final int REPEATS = Integer.parseInt(env("LIVE_EVAL_REPEATS", "1"));
    private static final Pattern CASES = Pattern.compile(env("LIVE_EVAL_CASES", ".*"));
    private static final boolean ENFORCE = Boolean.parseBoolean(env("LIVE_EVAL_ENFORCE", "false"));

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static AiStack stack() {
        ProviderHttp http = new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(10)), TestJson.applicationMapper());
        if (PROVIDER.equals("gemini")) {
            String key = System.getenv("GEMINI_API_KEY");
            assumeTrue(key != null && !key.isBlank(), "GEMINI_API_KEY is not set");
            LlmProvider gemini = new GeminiLlmProvider(http, TestJson.applicationMapper(),
                    URI.create("https://generativelanguage.googleapis.com/v1beta"), key, Clock.systemUTC());
            return AiStack.live(gemini, MODEL, Duration.ofSeconds(90), ThinkingLevel.LOW);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            String tags = client.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL), "Ollama does not have " + MODEL);
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
        return AiStack.live(new OllamaLlmProvider(http, TestJson.applicationMapper(), URI.create(OLLAMA), Clock.systemUTC()), MODEL,
                Duration.ofMinutes(5));
    }

    @Test
    void theEvaluatorIsComparedWithTheExpectedBehaviourOfEveryCase() throws Exception {
        AiStack stack = stack();
        ScoringPolicy policy = ShippedScoring.policy();
        List<AnswerEvaluationCase> cases = AnswerEvaluationCase.load().stream().filter(c -> CASES.matcher(c.id()).matches()).toList();
        List<CaseResult> results = new ArrayList<>();
        for (int run = 1; run <= REPEATS; run++) {
            for (AnswerEvaluationCase testCase : cases) {
                CaseResult result = evaluate(stack, policy, testCase, run);
                results.add(result);
                log.info("{} #{}: {} ({} ms)", testCase.id(), run,
                        result.evaluated() ? String.format(Locale.ROOT, "%.2f", result.score()) : "FAILED " + result.failure(), result.millis());
            }
        }

        AnswerEvaluationScorecard scorecard = new AnswerEvaluationScorecard(results);
        Map<String, String> setup = new LinkedHashMap<>();
        setup.put("Provider and model", PROVIDER + " " + MODEL);
        setup.put("Run", Instant.now().toString().substring(0, 16).replace('T', ' ') + " UTC, " + cases.size() + " cases x " + REPEATS + " run(s)");
        setup.put("Grounding", "none (no knowledge base: the model's own knowledge, the prompt and the server's rules)");
        setup.put("Scoring", "the shipped application.yml: skill-profile weights, rating rules, confidence policy");
        String name = "answer-evaluation-" + PROVIDER + "-" + MODEL.replaceAll("[^A-Za-z0-9.]+", "-");
        Path reports = Path.of(System.getProperty("answer.eval.reports", "../ai-evals/reports"));
        Files.createDirectories(reports);
        Files.writeString(reports.resolve(name + ".md"), scorecard.markdown("Answer evaluation: " + PROVIDER + " " + MODEL, setup));
        Files.writeString(reports.resolve(name + ".jsonl"), raw(results));
        log.info("\n{}", scorecard.markdown("Answer evaluation: " + PROVIDER + " " + MODEL, setup));

        assertThat(scorecard.failureRate()).as("evaluations that could not run at all").isLessThan(0.5);
        if (ENFORCE) {
            assertThat(scorecard.gates()).as("pass criteria").allMatch(AnswerEvaluationScorecard.Gate::passed);
        }
    }

    /** One evaluation, through the real agent and the application's scoring. */
    static CaseResult evaluate(AiStack stack, ScoringPolicy policy, AnswerEvaluationCase testCase, int run) {
        EvaluationRequest request = new EvaluationRequest(UUID.randomUUID(), UUID.randomUUID(), testCase.evaluator(), testCase.topicRef(),
                testCase.difficulty(), testCase.question(), testCase.expectedKeyPoints(), null, testCase.answer(), null, thread(testCase), "",
                policy.focus(testCase.evaluator(), testCase.skill()), List.of());
        int callsBefore = stack.recorder.statuses().size();
        long start = System.nanoTime();
        AnswerAssessment assessment;
        try {
            assessment = stack.answerEvaluator().evaluate(request);
        } catch (RuntimeException failure) {
            return CaseResult.failed(testCase, run, lastStatus(stack, callsBefore), (System.nanoTime() - start) / 1_000_000,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        Map<Criterion, String> rationales = new EnumMap<>(Criterion.class);
        for (Map.Entry<Criterion, CriterionRating> entry : assessment.ratings().entrySet()) {
            ratings.put(entry.getKey(), entry.getValue().rating());
            rationales.put(entry.getKey(), entry.getValue().rationale());
        }
        AnswerScorer.ScoredAnswer scored = AnswerScorer.score(policy.rubric(testCase.evaluator(), testCase.skill()), ratings, rationales,
                new RatingRules.Evidence(testCase.keyPoints().size(), assessment.keyPoints(), assessment.factualErrors().size(), assessment.flags()),
                assessment.confidence());
        Map<Criterion, Integer> scoredRatings = new EnumMap<>(Criterion.class);
        List<String> capped = new ArrayList<>();
        for (CriterionScore score : scored.criterionScores()) {
            scoredRatings.put(score.criterion(), score.rating());
            if (score.capped()) {
                capped.add(score.criterion() + " " + score.modelRating() + "->" + score.rating() + " " + score.cappedBy());
            }
        }
        Map<String, KeyPointStatus> keyPoints = new LinkedHashMap<>();
        assessment.keyPoints().forEach(point -> keyPoints.put(point.keyPointId(), point.status()));
        return new CaseResult(testCase, run, scored.overall(), ratings, scoredRatings, keyPoints,
                assessment.factualErrors().stream().map(FactualError::claim).toList(), scored.flags(),
                assessment.confidence() == null ? null : assessment.confidence().level(),
                scored.confidence() == null ? null : scored.confidence().level(), capped, lastStatus(stack, callsBefore), millis, null);
    }

    /** The earlier turns of a follow-up, as the application sends them to the evaluator. */
    private static List<ThreadTurn> thread(AnswerEvaluationCase testCase) {
        return testCase.thread() == null ? List.of()
                : testCase.thread().stream().map(turn -> new ThreadTurn(turn.question(), turn.answer(), false)).toList();
    }

    /** How the structured call of this evaluation ended: OK when the first output was valid, REPAIRED after one repair. */
    private static LlmCallStatus lastStatus(AiStack stack, int callsBefore) {
        List<LlmCallStatus> statuses = stack.recorder.statuses();
        if (statuses.size() <= callsBefore) {
            return null;
        }
        List<LlmCallStatus> ours = statuses.subList(callsBefore, statuses.size());
        return ours.contains(LlmCallStatus.INVALID_OUTPUT) && ours.getLast() == LlmCallStatus.REPAIRED ? LlmCallStatus.REPAIRED : ours.getLast();
    }

    private static String raw(List<CaseResult> results) {
        StringBuilder lines = new StringBuilder();
        for (CaseResult result : results) {
            ObjectNode line = TestJson.applicationMapper().createObjectNode();
            line.put("id", result.testCase().id()).put("run", result.run()).put("category", result.testCase().category().name());
            line.put("humanScore", result.testCase().expected().humanScore());
            if (result.score() != null) {
                line.put("score", result.score());
            }
            ObjectNode model = line.putObject("modelRatings");
            result.modelRatings().forEach((criterion, rating) -> model.put(criterion.name(), rating));
            ObjectNode scored = line.putObject("scoredRatings");
            result.scoredRatings().forEach((criterion, rating) -> scored.put(criterion.name(), rating));
            ObjectNode keyPoints = line.putObject("keyPoints");
            result.keyPoints().forEach((id, status) -> keyPoints.put(id, status.name()));
            ArrayNode errors = line.putArray("factualErrors");
            result.factualErrors().forEach(errors::add);
            ArrayNode flags = line.putArray("flags");
            result.flags().forEach(flag -> flags.add(flag.name()));
            line.put("modelConfidence", result.modelConfidence() == null ? null : result.modelConfidence().name());
            line.put("confidence", result.confidence() == null ? null : result.confidence().name());
            ArrayNode capped = line.putArray("lowered");
            result.capped().forEach(capped::add);
            line.put("callStatus", result.callStatus() == null ? null : result.callStatus().name());
            line.put("millis", result.millis());
            line.put("failure", result.failure());
            lines.append(line).append('\n');
        }
        return lines.toString();
    }
}
