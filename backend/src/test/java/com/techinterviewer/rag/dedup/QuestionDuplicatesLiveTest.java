package com.techinterviewer.rag.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.techinterviewer.ai.provider.EmbeddingProvider;
import com.techinterviewer.ai.provider.EmbeddingPurpose;
import com.techinterviewer.ai.provider.EmbeddingRequest;
import com.techinterviewer.ai.provider.ProviderHttp;
import com.techinterviewer.ai.provider.gemini.GeminiEmbeddingProvider;
import com.techinterviewer.ai.provider.ollama.OllamaEmbeddingProvider;
import com.techinterviewer.TestJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calibrates {@code app.rag.deduplication.min-similarity} for a real embedding model on
 * {@code ai-evals/datasets/question-duplicates.jsonl}: paraphrases of one question (must be caught) against different
 * questions on the same topic (must be let through: a topic with a quota of two asks two of them) and questions on other
 * topics. Opt-in (tag {@code live}); needs Ollama with the model pulled, or a Gemini key.
 *
 * <pre>
 * ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=QuestionDuplicatesLiveTest
 * LIVE_EMBEDDING_PROVIDER=gemini LIVE_EMBEDDING_MODEL=gemini-embedding-2 GEMINI_API_KEY=... (same command) for Gemini
 * LIVE_DEDUP_THRESHOLD=0.85 checks another threshold; LIVE_DEDUP_PURPOSE=DOCUMENT embeds with another task (ablation)
 * </pre>
 *
 * It logs the similarity range of each kind of pair and the accuracy at a sweep of thresholds, and asserts that the
 * threshold under test flags no different question as a repeat (a false repeat costs a question; a missed paraphrase is
 * still caught by the lexical check when the wording is close) while catching most paraphrases.
 */
@Tag("live")
class QuestionDuplicatesLiveTest {

    private static final Logger log = LoggerFactory.getLogger(QuestionDuplicatesLiveTest.class);
    private static final String PROVIDER = System.getenv().getOrDefault("LIVE_EMBEDDING_PROVIDER", "ollama");
    private static final String MODEL = System.getenv().getOrDefault("LIVE_EMBEDDING_MODEL", "embeddinggemma");
    private static final String OLLAMA = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    /** The configured value for the model: 0.90 for Gemini (application.yml), 0.80 for embeddinggemma (local-ollama profile). */
    private static final double THRESHOLD = Double.parseDouble(System.getenv().getOrDefault("LIVE_DEDUP_THRESHOLD",
            MODEL.startsWith("gemini") ? "0.90" : "0.80"));
    /** The purpose the texts are embedded with: SIMILARITY as in production; QUERY or DOCUMENT to compare (ablation). */
    private static final EmbeddingPurpose PURPOSE = EmbeddingPurpose.valueOf(System.getenv().getOrDefault("LIVE_DEDUP_PURPOSE", "SIMILARITY"));

    record Pair(String id, String kind, boolean duplicate, String a, String b) {
    }

    static List<Pair> dataset() throws Exception {
        List<Pair> pairs = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(System.getProperty("dedup.eval.dataset",
                "../ai-evals/datasets/question-duplicates.jsonl")))) {
            if (line.isBlank()) {
                continue;
            }
            var node = TestJson.applicationMapper().readTree(line);
            pairs.add(new Pair(node.get("id").asString(), node.get("kind").asString(), node.get("duplicate").asBoolean(),
                    node.get("a").asString(), node.get("b").asString()));
        }
        return pairs;
    }

    private static EmbeddingProvider provider() {
        ProviderHttp http = new ProviderHttp(ProviderHttp.defaultClient(Duration.ofSeconds(10)), TestJson.applicationMapper());
        if (PROVIDER.equals("gemini")) {
            String key = System.getenv("GEMINI_API_KEY");
            assumeTrue(key != null, "GEMINI_API_KEY is not set");
            return new GeminiEmbeddingProvider(http, TestJson.applicationMapper(),
                    URI.create("https://generativelanguage.googleapis.com/v1beta"), key, Clock.systemUTC());
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            String tags = client.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL), "Ollama does not have " + MODEL);
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
        return new OllamaEmbeddingProvider(http, TestJson.applicationMapper(), URI.create(OLLAMA), Clock.systemUTC());
    }

    @Test
    void theThresholdSeparatesParaphrasesFromDifferentQuestionsOnTheSameTopic() throws Exception {
        List<Pair> pairs = dataset();
        EmbeddingProvider provider = provider();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        pairs.forEach(pair -> {
            vectors.put(pair.a(), null);
            vectors.put(pair.b(), null);
        });
        List<String> texts = List.copyOf(vectors.keySet());
        for (int start = 0; start < texts.size(); start += 50) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + 50));
            List<float[]> embedded = provider.embed(new EmbeddingRequest(MODEL, batch, PURPOSE, 768,
                    Duration.ofMinutes(2))).vectors();
            for (int i = 0; i < batch.size(); i++) {
                vectors.put(batch.get(i), embedded.get(i));
            }
        }
        Map<Pair, Double> similarity = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            similarity.put(pair, cosine(vectors.get(pair.a()), vectors.get(pair.b())));
        }

        StringBuilder report = new StringBuilder("\nQuestion de-duplication, " + PROVIDER + " " + MODEL + " (" + PURPOSE + "), " + pairs.size() + " pairs\n");
        for (String kind : List.of("paraphrase", "same-topic", "other-topic")) {
            var values = similarity.entrySet().stream().filter(e -> e.getKey().kind().equals(kind)).mapToDouble(Map.Entry::getValue)
                    .sorted().toArray();
            report.append(String.format(Locale.ROOT, "  %-12s n=%2d  min %.3f  median %.3f  max %.3f%n", kind, values.length, values[0],
                    values[values.length / 2], values[values.length - 1]));
        }
        report.append("  threshold  caught paraphrases  false repeats  accuracy\n");
        for (double t = 0.60; t <= 0.981; t += 0.02) {
            Score score = score(similarity, t);
            report.append(String.format(Locale.ROOT, "  %.2f       %2d/%2d               %2d             %.3f%n", t, score.caught(),
                    score.duplicates(), score.falseRepeats(), score.accuracy()));
        }
        similarity.forEach((pair, value) -> {
            boolean flagged = value >= THRESHOLD;
            if (flagged != pair.duplicate()) {
                report.append(String.format(Locale.ROOT, "  wrong at %.2f: %s (%s) %.3f%n", THRESHOLD, pair.id(), pair.kind(), value));
            }
        });
        log.info(report.toString());

        Score atThreshold = score(similarity, THRESHOLD);
        assertThat(atThreshold.falseRepeats()).as("different questions flagged as repeats at " + THRESHOLD + report).isZero();
        assertThat((double) atThreshold.caught() / atThreshold.duplicates()).as("paraphrases caught at " + THRESHOLD + report)
                .isGreaterThanOrEqualTo(0.6);
    }

    private record Score(int caught, int duplicates, int falseRepeats, double accuracy) {
    }

    private static Score score(Map<Pair, Double> similarity, double threshold) {
        int caught = 0;
        int duplicates = 0;
        int falseRepeats = 0;
        int correct = 0;
        for (var entry : similarity.entrySet()) {
            boolean flagged = entry.getValue() >= threshold;
            if (entry.getKey().duplicate()) {
                duplicates++;
                caught += flagged ? 1 : 0;
            } else if (flagged) {
                falseRepeats++;
            }
            correct += flagged == entry.getKey().duplicate() ? 1 : 0;
        }
        return new Score(caught, duplicates, falseRepeats, (double) correct / similarity.size());
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
