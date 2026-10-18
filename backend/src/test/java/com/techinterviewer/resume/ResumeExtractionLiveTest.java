package com.techinterviewer.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.TestJson;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import com.techinterviewer.ai.provider.ollama.OllamaLlmProvider;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.resume.domain.ResumeExperience;
import com.techinterviewer.resume.domain.ResumeFact;
import com.techinterviewer.resume.domain.ResumeProfile;
import com.techinterviewer.resume.parse.ResumeParser;
import com.techinterviewer.resume.parse.ResumeText;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real résumé extractor and verifier on every case of {@code ai-evals/datasets/resume-extraction.jsonl} and checks what the
 * labels expect: substance, the injection flag, the stated years, the experiences and technologies kept, and that nothing injected
 * or private reaches the profile. Writes {@code ai-evals/reports/resume-extraction-<provider>-<model>.md} with the reasons of every
 * dropped item. Opt-in (tag {@code live}), configured like {@code DesignEvaluationLiveTest}:
 *
 * <pre>
 * ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=ResumeExtractionLiveTest
 * </pre>
 */
@Tag("live")
class ResumeExtractionLiveTest {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionLiveTest.class);
    private static final String PROVIDER = env("LIVE_EVAL_PROVIDER", "ollama");
    private static final String OLLAMA = env("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = env("LIVE_EVAL_MODEL", PROVIDER.equals("gemini")
            ? env("GEMINI_MODEL", "gemini-3.8-flash") : env("OLLAMA_MODEL", "qwen3.5:4b"));
    private static final YearMonth NOW = YearMonth.of(2026, 10);

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

    record Result(ResumeEvaluationCase testCase, long millis, ResumeProfile profile, boolean flagged, List<String> dropped, List<String> misses,
            String failure) {
    }

    @Test
    void theExtractorKeepsWhatTheResumeSaysAndNothingElse() throws Exception {
        AiStack stack = stack();
        ResumeVocabulary vocabulary = ResumeVocabulary.load();
        JsonMapper json = TestJson.applicationMapper();
        List<Result> results = new ArrayList<>();
        for (ResumeEvaluationCase testCase : ResumeEvaluationCase.load()) {
            String text = ResumeText.withoutContactDetails(ResumeParser.parse(testCase.resume().getBytes(StandardCharsets.UTF_8)).text());
            long start = System.nanoTime();
            ResumeExtraction extraction;
            try {
                extraction = stack.resumeExtractor().extract(UUID.randomUUID(), text);
            } catch (RuntimeException failure) {
                results.add(new Result(testCase, (System.nanoTime() - start) / 1_000_000, null, false, List.of(), List.of(),
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                continue;
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            ResumeProfileVerifier.Result verified = ResumeProfileVerifier.verify(extraction, vocabulary, NOW);
            ResumeProfile profile = verified.profile();
            ResumeEvaluationCase.Expected expected = testCase.expected();
            List<String> misses = new ArrayList<>();
            if (profile.hasSubstance() != expected.substance()) {
                misses.add("substance " + profile.hasSubstance() + " but expected " + expected.substance());
            }
            if (extraction.injectionSuspected() != expected.injection()) {
                misses.add("injection flag " + extraction.injectionSuspected());
            }
            Integer stated = profile.yearsOfExperience() != null && profile.yearsOfExperience().basis() == com.techinterviewer.resume.domain.YearsOfExperience.Basis.STATED
                    ? profile.yearsOfExperience().years() : null;
            if (!java.util.Objects.equals(stated, expected.statedYears())) {
                misses.add("stated years " + stated + " but expected " + expected.statedYears());
            }
            for (String statement : expected.experiences()) {
                if (profile.experiences().stream().map(ResumeExperience::text).noneMatch(text1 -> text1.startsWith(statement))) {
                    misses.add("missing experience: " + statement);
                }
            }
            List<String> technologies = profile.technologies().stream().map(ResumeFact::name).toList();
            expected.technologies().stream().filter(name -> !technologies.contains(name)).forEach(name -> misses.add("missing technology " + name));
            String stored = json.writeValueAsString(profile);
            expected.mustNotContain().stream().filter(stored::contains).forEach(forbidden -> misses.add("profile contains: " + forbidden));
            results.add(new Result(testCase, millis, profile, extraction.injectionSuspected(), verified.dropped(), misses, null));
            log.info("{}: {} ms, {} experiences, {} dropped, misses {}", testCase.id(), millis, profile.experiences().size(), verified.dropped().size(),
                    misses);
        }
        String report = report(results);
        Path reports = Path.of(System.getProperty("answer.eval.reports", "../ai-evals/reports"));
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("resume-extraction-" + PROVIDER + "-" + MODEL.replaceAll("[^A-Za-z0-9.]+", "-") + ".md"), report);
        log.info("\n{}", report);
        assertThat(results.stream().filter(result -> result.failure() != null).count()).as("extractions that could not run").isLessThan(2);
        // Hard guarantees, whatever the model: nothing injected or private is ever stored.
        assertThat(results).allSatisfy(result -> assertThat(result.misses()).noneMatch(miss -> miss.startsWith("profile contains")));
    }

    private static String report(List<Result> results) {
        StringBuilder text = new StringBuilder("# Résumé extraction: ").append(PROVIDER).append(' ').append(MODEL).append("\n\n");
        long met = results.stream().filter(result -> result.failure() == null && result.misses().isEmpty()).count();
        text.append("Cases meeting every expectation: ").append(met).append(" of ").append(results.size()).append("\n\n");
        text.append("| Case | ms | Experiences | Technologies | Years | Flagged | Dropped | Misses |\n|---|---|---|---|---|---|---|---|\n");
        for (Result result : results) {
            if (result.failure() != null) {
                text.append("| ").append(result.testCase().id()).append(" | ").append(result.millis()).append(" | | | | | | ").append(result.failure()).append(" |\n");
                continue;
            }
            ResumeProfile profile = result.profile();
            text.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %s | %s | %d | %s |%n", result.testCase().id(), result.millis(),
                    profile.experiences().size(), profile.technologies().size(),
                    profile.yearsOfExperience() == null ? "-" : profile.yearsOfExperience().years() + " " + profile.yearsOfExperience().basis(),
                    result.flagged(), result.dropped().size(), String.join("; ", result.misses())));
        }
        text.append("\n## Dropped items\n\n");
        for (Result result : results) {
            if (result.failure() == null && !result.dropped().isEmpty()) {
                text.append("**").append(result.testCase().id()).append("**\n\n");
                result.dropped().forEach(reason -> text.append("- ").append(reason).append('\n'));
                text.append('\n');
            }
        }
        return text.toString();
    }
}
