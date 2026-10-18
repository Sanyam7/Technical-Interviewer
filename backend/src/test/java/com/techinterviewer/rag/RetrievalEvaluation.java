package com.techinterviewer.rag;

import com.techinterviewer.TestJson;
import com.techinterviewer.rag.retrieval.HybridRetriever;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import com.techinterviewer.rag.retrieval.RetrievedChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures retrieval quality on the labelled set {@code ai-evals/datasets/retrieval.jsonl} (see its README): recall@5,
 * hit@5 and MRR@5 over the queries the knowledge base answers, and abstention (nothing returned) over those it does not.
 * A relevant label {@code path#Heading} matches every chunk of that section.
 */
public final class RetrievalEvaluation {

    public static final int K = 5;

    /**
     * Which parts of retrieval a run uses, for ablations: the full hybrid search with the technology scope and topic of
     * each case, the vector half alone (no keyword terms), and the vector half without scope or topic either (plain
     * nearest-neighbour search over the whole knowledge base). Keyword-only runs use a retriever whose query embedding
     * fails, which is what production does when the embedding model is down.
     */
    public enum Mode { HYBRID, VECTOR_ONLY, VECTOR_UNSCOPED }

    /** One labelled query. */
    public record Case(String id, String kind, String query, List<String> keywords, Set<Technology> technologies,
            @Nullable String topic, List<String> relevant) {

        boolean answerable() {
            return !relevant.isEmpty();
        }

        RetrievalQuery toQuery(Mode mode) {
            return switch (mode) {
                case HYBRID -> new RetrievalQuery(query, keywords, technologies, Set.of(), topic, null, null, K);
                case VECTOR_ONLY -> new RetrievalQuery(query, List.of(), technologies, Set.of(), topic, null, null, K);
                case VECTOR_UNSCOPED -> new RetrievalQuery(query, List.of(), Set.of(), Set.of(), null, null, null, K);
            };
        }
    }

    /** What retrieval returned for one case. */
    public record CaseResult(String id, List<String> retrieved, int firstRelevantRank, int relevantFound, int relevantTotal,
            @Nullable Double topSimilarity) {
    }

    /** The metrics of one run. */
    public record Report(String label, int answerable, int unanswerable, double recallAt5, double hitAt5, double mrrAt5,
            double abstention, List<CaseResult> cases) {

        public String summary() {
            return String.format(Locale.ROOT, "%s: recall@5 %.3f, hit@5 %.3f, MRR@5 %.3f over %d answerable queries; abstention %.3f over %d unanswerable",
                    label, recallAt5, hitAt5, mrrAt5, answerable, abstention, unanswerable);
        }
    }

    private RetrievalEvaluation() {
    }

    public static List<Case> load() {
        JsonMapper json = TestJson.applicationMapper();
        List<Case> cases = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(dataset())) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = json.readTree(line);
                Set<Technology> technologies = row.get("technologies").valueStream()
                        .map(slug -> Technology.fromSlug(slug.asString()).orElseThrow(() -> new IllegalArgumentException("unknown technology " + slug)))
                        .collect(Collectors.toSet());
                cases.add(new Case(row.get("id").asString(), row.get("kind").asString(), row.get("query").asString(),
                        row.get("keywords").valueStream().map(JsonNode::asString).toList(), technologies,
                        row.get("topic").isNull() ? null : row.get("topic").asString(),
                        row.get("relevant").valueStream().map(JsonNode::asString).toList()));
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return cases;
    }

    /** The dataset: the path the build passes, else found by walking up from the working directory. */
    static Path dataset() {
        String configured = System.getProperty("rag.eval.dataset");
        if (configured != null && Files.exists(Path.of(configured))) {
            return Path.of(configured);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("ai-evals/datasets/retrieval.jsonl");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("ai-evals/datasets/retrieval.jsonl not found");
    }

    public static Report run(String label, List<Case> cases, HybridRetriever retriever) {
        return run(label, cases, retriever, Mode.HYBRID);
    }

    public static Report run(String label, List<Case> cases, HybridRetriever retriever, Mode mode) {
        List<List<RetrievedChunk>> results = retriever.retrieveAll(cases.stream().map(labelled -> labelled.toQuery(mode)).toList(), null);
        List<CaseResult> outcomes = new ArrayList<>();
        double recall = 0;
        double hits = 0;
        double reciprocal = 0;
        int answerable = 0;
        int abstained = 0;
        for (int i = 0; i < cases.size(); i++) {
            Case labelled = cases.get(i);
            List<RetrievedChunk> found = results.get(i);
            Set<String> foundSections = new LinkedHashSet<>();
            int first = 0;
            for (int rank = 0; rank < found.size(); rank++) {
                String section = sectionOf(labelled, found.get(rank));
                if (section != null) {
                    foundSections.add(section);
                    if (first == 0) {
                        first = rank + 1;
                    }
                }
            }
            if (labelled.answerable()) {
                answerable++;
                recall += (double) foundSections.size() / labelled.relevant().size();
                hits += first > 0 ? 1 : 0;
                reciprocal += first > 0 ? 1.0 / first : 0;
            } else if (found.isEmpty()) {
                abstained++;
            }
            outcomes.add(new CaseResult(labelled.id(),
                    found.stream().map(chunk -> chunk.sourcePath() + "#" + lastHeading(chunk)).toList(), first, foundSections.size(),
                    labelled.relevant().size(), found.isEmpty() ? null : found.getFirst().similarity()));
        }
        int unanswerable = cases.size() - answerable;
        return new Report(label, answerable, unanswerable, recall / answerable, hits / answerable, reciprocal / answerable,
                unanswerable == 0 ? 1 : (double) abstained / unanswerable, outcomes);
    }

    /** The relevant label a retrieved chunk belongs to, or null. */
    private static @Nullable String sectionOf(Case labelled, RetrievedChunk chunk) {
        for (String label : labelled.relevant()) {
            int hash = label.indexOf('#');
            String path = label.substring(0, hash);
            String heading = label.substring(hash + 1);
            if (path.equals(chunk.sourcePath()) && chunk.headingPath() != null && chunk.headingPath().endsWith(" › " + heading)) {
                return label;
            }
        }
        return null;
    }

    private static String lastHeading(RetrievedChunk chunk) {
        String path = chunk.headingPath() == null ? "" : chunk.headingPath();
        return path.substring(path.lastIndexOf(" › ") + 3);
    }

    /** Writes the report as JSON under {@code target/rag-eval} for inspection, and returns its path. */
    public static Path write(Report report) {
        try {
            Path dir = Path.of("target", "rag-eval").toAbsolutePath();
            Files.createDirectories(dir);
            Path file = dir.resolve(report.label() + ".json");
            Files.writeString(file, TestJson.applicationMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return file;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
