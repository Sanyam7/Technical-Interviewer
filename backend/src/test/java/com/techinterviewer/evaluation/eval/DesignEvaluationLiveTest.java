package com.techinterviewer.evaluation.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.provider.ollama.OllamaLlmProvider;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.design.DesignProblem;
import com.techinterviewer.design.DesignProblemBank;
import com.techinterviewer.evaluation.AnswerScorer;
import com.techinterviewer.evaluation.AssessmentEvidence;
import com.techinterviewer.evaluation.ScoringPolicy;
import com.techinterviewer.evaluation.ShippedScoring;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.DesignContext;
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

/**
 * Runs the real system design evaluator on every case of {@code ai-evals/datasets/design-evaluation.jsonl}, scores its output
 * exactly as the application does (quotes verified in the agent, then {@link AssessmentEvidence}, the rating rules and the shipped
 * weights), and checks what the dataset expects: the score band, ratings bounded per dimension, factual errors, flags, and that
 * an injection does not outscore its clean answer. Writes {@code ai-evals/reports/design-evaluation-<provider>-<model>.md}.
 * Opt-in (tag {@code live}), like {@link AnswerEvaluationLiveTest}, and configured by the same environment variables:
 *
 * <pre>
 * ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=DesignEvaluationLiveTest
 * </pre>
 */
@Tag("live")
class DesignEvaluationLiveTest {

    private static final Logger log = LoggerFactory.getLogger(DesignEvaluationLiveTest.class);
    private static final String PROVIDER = env("LIVE_EVAL_PROVIDER", "ollama");
    private static final String OLLAMA = env("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = env("LIVE_EVAL_MODEL", PROVIDER.equals("gemini")
            ? env("GEMINI_MODEL", "gemini-3.8-flash") : env("OLLAMA_MODEL", "qwen3.5:4b"));
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
            return AiStack.live(gemini, MODEL, Duration.ofSeconds(120), ThinkingLevel.LOW);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            String tags = client.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL), "Ollama does not have " + MODEL);
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
        return AiStack.live(new OllamaLlmProvider(http, TestJson.applicationMapper(), URI.create(OLLAMA), Clock.systemUTC()), MODEL,
                Duration.ofMinutes(8));
    }

    /** What one case came to: its score, its ratings after the rules, and every expectation it missed. */
    record Result(DesignEvaluationCase testCase, double score, Map<Criterion, Integer> ratings, List<String> capped, int errors,
            List<String> errorDimensions, List<String> errorClaims, Map<Criterion, String> rationales, boolean mismatch, boolean injectionFlag, long millis, List<String> misses, String failure) {
    }

    @Test
    void theDesignEvaluatorBehavesAsTheLabelsExpect() throws Exception {
        AiStack stack = stack();
        ScoringPolicy policy = ShippedScoring.policy();
        DesignProblemBank bank = DesignProblemBank.load();
        List<DesignEvaluationCase> cases = DesignEvaluationCase.load().stream().filter(c -> CASES.matcher(c.id()).matches()).toList();
        Map<String, Result> results = new LinkedHashMap<>();
        for (DesignEvaluationCase testCase : cases) {
            Result result = evaluate(stack, policy, bank.find(testCase.problem()).orElseThrow(), testCase);
            results.put(testCase.id(), result);
            log.info("{}: {} ({} ms) {}", testCase.id(), result.failure() == null ? String.format(Locale.ROOT, "%.2f", result.score()) : result.failure(),
                    result.millis(), result.misses());
        }
        List<String> invariance = new ArrayList<>();
        for (Result result : results.values()) {
            String of = result.testCase().expected().invarianceOf();
            if (of != null && results.containsKey(of) && result.failure() == null && results.get(of).failure() == null
                    && result.score() > results.get(of).score() + 1e-9) {
                invariance.add(result.testCase().id() + " scored " + result.score() + " above " + of + " (" + results.get(of).score() + ")");
            }
        }
        String report = report(results, invariance);
        Path reports = Path.of(System.getProperty("answer.eval.reports", "../ai-evals/reports"));
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("design-evaluation-" + PROVIDER + "-" + MODEL.replaceAll("[^A-Za-z0-9.]+", "-") + ".md"), report);
        log.info("\n{}", report);

        long failed = results.values().stream().filter(result -> result.failure() != null).count();
        assertThat(failed).as("evaluations that could not run at all").isLessThan(Math.max(1, cases.size() / 2));
        if (ENFORCE) {
            assertThat(results.values()).allMatch(result -> result.failure() == null && result.misses().isEmpty());
            assertThat(invariance).isEmpty();
        }
    }

    static Result evaluate(AiStack stack, ScoringPolicy policy, DesignProblem problem, DesignEvaluationCase testCase) {
        List<ThreadTurn> thread = testCase.thread().stream()
                .map(turn -> new ThreadTurn(turn.question().equals("PROBLEM") ? problem.statement() : turn.question(), turn.answer(), false))
                .toList();
        String question = testCase.question() == null ? problem.statement() : testCase.question();
        EvaluationRequest request = new EvaluationRequest(UUID.randomUUID(), UUID.randomUUID(), Evaluator.SYSTEM_DESIGN, null,
                testCase.difficulty(), question, problem.keyPoints(), null, testCase.answer(), null, thread, "",
                policy.focus(Evaluator.SYSTEM_DESIGN, ScoringPolicy.DESIGN_PROFILE), List.of(), null,
                new DesignContext(testCase.architecture(), testCase.probe()));
        long start = System.nanoTime();
        AnswerAssessment assessment;
        try {
            assessment = stack.answerEvaluator().evaluate(request);
        } catch (RuntimeException failure) {
            return new Result(testCase, 0, Map.of(), List.of(), 0, List.of(), List.of(), Map.of(), false, false, (System.nanoTime() - start) / 1_000_000, List.of(),
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        Map<Criterion, Integer> given = new EnumMap<>(Criterion.class);
        Map<Criterion, String> rationales = new EnumMap<>(Criterion.class);
        for (Map.Entry<Criterion, CriterionRating> entry : assessment.ratings().entrySet()) {
            given.put(entry.getKey(), entry.getValue().rating());
            rationales.put(entry.getKey(), entry.getValue().rationale());
        }
        AnswerScorer.ScoredAnswer scored = AnswerScorer.score(policy.rubric(Evaluator.SYSTEM_DESIGN, ScoringPolicy.DESIGN_PROFILE), given, rationales,
                AssessmentEvidence.quotes(assessment), AssessmentEvidence.design(problem.keyPoints().size(), assessment), assessment.confidence());
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        List<String> capped = new ArrayList<>();
        for (CriterionScore score : scored.criterionScores()) {
            ratings.put(score.criterion(), score.rating());
            if (score.capped()) {
                capped.add(score.criterion() + " " + score.modelRating() + "->" + score.rating() + " " + score.cappedBy());
            }
        }
        DesignEvaluationCase.Expected expected = testCase.expected();
        List<String> misses = new ArrayList<>();
        if (scored.overall() < expected.scoreBand().get(0) || scored.overall() > expected.scoreBand().get(1)) {
            misses.add("score " + scored.overall() + " outside " + expected.scoreBand());
        }
        expected.minRatings().forEach((criterion, min) -> {
            if (ratings.getOrDefault(criterion, 0) < min) {
                misses.add(criterion + " " + ratings.get(criterion) + " < " + min);
            }
        });
        expected.maxRatings().forEach((criterion, max) -> {
            if (ratings.getOrDefault(criterion, 0) > max) {
                misses.add(criterion + " " + ratings.get(criterion) + " > " + max);
            }
        });
        int errors = assessment.factualErrors().size();
        if (errors < expected.factualErrors().get(0) || errors > expected.factualErrors().get(1)) {
            misses.add(errors + " factual errors outside " + expected.factualErrors());
        }
        expected.mustFlag().forEach(flag -> {
            if (!scored.flags().contains(flag)) {
                misses.add("missing flag " + flag);
            }
        });
        return new Result(testCase, scored.overall(), ratings, capped, errors,
                assessment.factualErrors().stream().map(FactualError::criterion).map(String::valueOf).toList(),
                assessment.factualErrors().stream().map(error -> error.criterion() + ": " + error.claim() + " -> " + error.correction()).toList(),
                rationales, scored.flags().contains(EvaluationFlag.EVAL_EVIDENCE_MISMATCH), scored.flags().contains(EvaluationFlag.INJECTION_SUSPECTED),
                millis, misses, null);
    }

    private static String report(Map<String, Result> results, List<String> invariance) {
        StringBuilder text = new StringBuilder("# Design evaluation: ").append(PROVIDER).append(' ').append(MODEL).append("\n\n")
                .append("Run ").append(Instant.now().toString(), 0, 16).append(" UTC, ").append(results.size())
                .append(" cases of ai-evals/datasets/design-evaluation.jsonl, no knowledge base, shipped scoring (system-design.v2).\n\n")
                .append("| Case | Score | Expected | Misses | Lowered by the rules | Errors (dimension) | Quote mismatch | Seconds |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        long passed = 0;
        for (Result result : results.values()) {
            if (result.failure() != null) {
                text.append("| ").append(result.testCase().id()).append(" | failed | | ").append(result.failure().replace('|', '/')).append(" | | | | ")
                        .append(result.millis() / 1000).append(" |\n");
                continue;
            }
            if (result.misses().isEmpty()) {
                passed++;
            }
            text.append("| ").append(result.testCase().id())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.score()))
                    .append(" | ").append(result.testCase().expected().scoreBand())
                    .append(" | ").append(result.misses().isEmpty() ? "none" : String.join("; ", result.misses()))
                    .append(" | ").append(result.capped().isEmpty() ? "none" : String.join("; ", result.capped()))
                    .append(" | ").append(result.errors()).append(result.errors() > 0 ? " " + result.errorDimensions() : "")
                    .append(" | ").append(result.mismatch() ? "yes" : "no")
                    .append(" | ").append(result.millis() / 1000).append(" |\n");
        }
        text.append("\n**Cases meeting every expectation:** ").append(passed).append(" of ").append(results.size()).append(".\n\n")
                .append("**Injection invariance:** ").append(invariance.isEmpty() ? "held" : String.join("; ", invariance)).append(".\n\n")
                .append("Ratings after the rules, per case:\n\n");
        for (Result result : results.values()) {
            text.append("- ").append(result.testCase().id()).append(": ").append(result.ratings()).append('\n');
        }
        text.append("\nFactual errors the evaluator listed:\n\n");
        for (Result result : results.values()) {
            result.errorClaims().forEach(claim -> text.append("- ").append(result.testCase().id()).append(": ").append(claim).append('\n'));
        }
        text.append("\nRationales of the architecture rating:\n\n");
        for (Result result : results.values()) {
            text.append("- ").append(result.testCase().id()).append(": ")
                    .append(result.rationales().getOrDefault(Criterion.HIGH_LEVEL_DESIGN, "")).append('\n');
        }
        return text.toString();
    }
}
