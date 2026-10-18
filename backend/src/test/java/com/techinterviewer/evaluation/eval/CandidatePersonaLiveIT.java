package com.techinterviewer.evaluation.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.InterviewApiTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Whole interviews, through the HTTP API and the real AI layer, by three simulated candidates: an excellent one, an average one
 * and a weak one. The application runs with the shipped {@code local-ollama} profile (a real local model for every task, real
 * embeddings, the curated knowledge base) and a real PostgreSQL. Opt-in (tag {@code live}).
 *
 * <pre>
 * ./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CandidatePersonaLiveIT
 * OLLAMA_MODEL=... OLLAMA_BASE_URL=...      another model or host
 * LIVE_EVAL_ENFORCE=true                    fail unless the personas are told apart (see below)
 * </pre>
 *
 * <p>The candidates are deterministic, so a run measures the interviewer and not a second model. Each reads the hidden key points
 * of the question it is asked (from the database; the API never shows them) and answers from them:
 * <ul>
 *   <li>EXCELLENT states every key point and how it would check it;
 *   <li>AVERAGE states the first half of them, hedged;
 *   <li>WEAK says it is not sure and names the topic.
 * </ul>
 * The clock is moved by each question's suggested minutes, so the interview ends by its plan or its time, as a real one does.
 *
 * <p>What it measures, written to {@code ai-evals/reports/persona-interviews-<model>.md}: the outcome per persona (score,
 * recommendation, questions, difficulties), and over every model call of the run: structured-output validity, model failures,
 * the latency of each turn (wall clock), duplicate questions (residual near-duplicates by embedding similarity, and generation attempts the
 * de-duplication refused) and the relevance of the passages questions cite (same technology, same topic). The checks: the
 * personas come out in their order (excellent above average above weak), the weak one gets no positive recommendation, and no
 * answer is left unevaluated. Reported always; enforced with {@code LIVE_EVAL_ENFORCE}.
 */
@Tag("live")
@ActiveProfiles("local-ollama")
class CandidatePersonaLiveIT extends InterviewApiTestBase {

    private static final Logger log = LoggerFactory.getLogger(CandidatePersonaLiveIT.class);
    private static final String OLLAMA = env("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL = env("OLLAMA_MODEL", "qwen3.5:4b");
    private static final String EMBEDDING_MODEL = env("OLLAMA_EMBEDDING_MODEL", "embeddinggemma");
    private static final boolean ENFORCE = Boolean.parseBoolean(env("LIVE_EVAL_ENFORCE", "false"));
    /** The near-duplicate threshold of embeddinggemma (ai-evals/datasets/README.md, question-duplicates). */
    private static final double DUPLICATE_SIMILARITY = 0.80;
    private static final int MAX_TURNS = 24;

    private static final String CONFIGURATION = """
            {"role":"BACKEND","experienceLevel":"MID","difficulty":"MEDIUM","durationMinutes":30,
             "interviewType":"TECHNICAL","skills":["java","postgresql"]}""";

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void theModelsAreAvailable() {
        try (HttpClient http = HttpClient.newHttpClient()) {
            String tags = http.send(HttpRequest.newBuilder(URI.create(OLLAMA + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assumeTrue(tags.contains("\"" + MODEL) && tags.contains("\"" + EMBEDDING_MODEL),
                    "Ollama needs " + MODEL + " and " + EMBEDDING_MODEL);
        } catch (Exception unreachable) {
            assumeTrue(false, "Ollama is not reachable at " + OLLAMA);
        }
    }

    @DynamicPropertySource
    static void localModel(DynamicPropertyRegistry registry) {
        registry.add("app.ai.providers.ollama.base-url", () -> OLLAMA);
        registry.add("app.code-execution.enabled", () -> "false");
    }

    /** A simulated candidate. */
    enum Persona {
        EXCELLENT, AVERAGE, WEAK;

        String introduction() {
            return switch (this) {
                case EXCELLENT -> "I am a backend engineer with six years of Java and PostgreSQL. I led the move of our order service to "
                        + "event-driven processing, cut p99 latency from 800 to 120 milliseconds by fixing N+1 queries and adding "
                        + "covering indexes, and I review most of the concurrency code in my team.";
                case AVERAGE -> "I have worked with Java and Spring for about three years, mostly on REST services backed by "
                        + "PostgreSQL. I have written some queries and fixed a few performance problems.";
                case WEAK -> "I have done some Java at university and a bit at work.";
            };
        }

        String answer(String topic, List<String> keyPoints) {
            if (keyPoints.isEmpty()) {
                return introduction();
            }
            return switch (this) {
                case EXCELLENT -> {
                    StringBuilder text = new StringBuilder("Let me go through it step by step. ");
                    for (int i = 0; i < keyPoints.size(); i++) {
                        text.append(i == 0 ? "First, " : i == keyPoints.size() - 1 ? "Finally, " : "Next, ")
                                .append(sentence(keyPoints.get(i))).append(' ');
                    }
                    yield text.append("In practice I would confirm this with a focused test and by measuring it under realistic load.")
                            .toString();
                }
                case AVERAGE -> {
                    List<String> half = keyPoints.subList(0, (keyPoints.size() + 1) / 2);
                    StringBuilder text = new StringBuilder("I think ");
                    for (int i = 0; i < half.size(); i++) {
                        text.append(i == 0 ? "" : "Also, ").append(sentence(half.get(i))).append(' ');
                    }
                    yield text.append("I am less sure about the rest of it.").toString();
                }
                case WEAK -> "I am not sure about this one. I think it is related to " + topic
                        + ", but I have not used it much in practice, so I would have to look it up.";
            };
        }

        private static String sentence(String keyPoint) {
            String text = keyPoint.strip();
            text = Character.toLowerCase(text.charAt(0)) + text.substring(1);
            return text.endsWith(".") ? text : text + ".";
        }
    }

    /** What one simulated interview produced. */
    record Outcome(Persona persona, UUID interview, Double score, String recommendation, String reportStatus, int questions,
            List<Integer> difficulties, int followUps, List<Double> answerScores, int pendingOrUnassessable, List<Long> turnMillis,
            int nextQuestionFailures, String endReason) {
    }

    @Test
    void threeCandidatesOfKnownQualityAreToldApartAndTheAiLayerIsMeasured() throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        Instant started = Instant.now();
        for (Persona persona : Persona.values()) {
            outcomes.add(interview(persona));
            log.info("{}: score {} recommendation {} after {} questions", persona, outcomes.getLast().score(),
                    outcomes.getLast().recommendation(), outcomes.getLast().questions());
        }
        List<UUID> ids = outcomes.stream().map(Outcome::interview).toList();
        String report = report(outcomes, ids, Duration.between(started, Instant.now()));
        Path reports = Path.of(System.getProperty("answer.eval.reports", "../ai-evals/reports"));
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("persona-interviews-ollama-" + MODEL.replaceAll("[^A-Za-z0-9.]+", "-") + ".md"), report);
        log.info("\n{}", report);

        assertThat(outcomes).as("every interview produced a report").allMatch(outcome -> outcome.reportStatus().equals("READY"));
        if (ENFORCE) {
            Outcome excellent = outcomes.get(0);
            Outcome average = outcomes.get(1);
            Outcome weak = outcomes.get(2);
            assertThat(excellent.score()).isGreaterThan(average.score());
            assertThat(average.score()).isGreaterThan(weak.score());
            assertThat(weak.recommendation()).isIn(null, "NOT_YET");
            assertThat(outcomes).allMatch(outcome -> outcome.pendingOrUnassessable() == 0);
        }
    }

    private Outcome interview(Persona persona) throws Exception {
        UUID user = newUser();
        UUID interview = createInterview(user, CONFIGURATION);
        postAs(user, "/interviews/" + interview + "/start").andExpect(status().isOk());
        List<Long> turnMillis = new ArrayList<>();
        int nextFailures = 0;
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            JsonNode session = body(getAs(user, "/interviews/" + interview).andExpect(status().isOk()));
            String state = session.get("state").asString();
            if (session.get("interviewComplete").asBoolean() || List.of("COMPLETED", "FINAL_EVALUATION", "ABANDONED").contains(state)) {
                break;
            }
            JsonNode question = session.get("currentQuestion");
            if (question.isNull()) {
                if (!session.get("awaitingNextQuestion").asBoolean() || nextFailures >= 3) {
                    break;
                }
                ResultActions next = postAs(user, "/interviews/" + interview + "/questions/next");
                if (next.andReturn().getResponse().getStatus() != 200) {
                    nextFailures++;
                }
                continue;
            }
            UUID questionId = UUID.fromString(question.get("id").asString());
            String content = persona.answer(question.path("topic").path("name").asString("the topic"), keyPoints(questionId));
            long start = System.nanoTime();
            postAs(user, "/interviews/" + interview + "/answers", json.createObjectNode()
                    .put("questionId", questionId.toString()).put("content", content).put("timeTakenSeconds", 120).toString())
                    .andExpect(status().isOk());
            turnMillis.add((System.nanoTime() - start) / 1_000_000);
            clock.advance(Duration.ofMinutes(Math.max(1, question.path("suggestedMinutes").asInt(3))));
        }
        JsonNode session = body(getAs(user, "/interviews/" + interview));
        String reason = session.get("interviewComplete").asBoolean() || session.get("state").asString().equals("FINAL_EVALUATION")
                ? "ACKNOWLEDGE_COMPLETE" : "CANDIDATE_ENDED";
        postAs(user, "/interviews/" + interview + "/finish", "{\"reason\":\"" + reason + "\"}");
        JsonNode report = body(getAs(user, "/interviews/" + interview + "/report"));

        List<Integer> difficulties = jdbc.sql("SELECT difficulty FROM questions WHERE interview_id = ? AND kind <> 'INTRODUCTION' ORDER BY sequence_no")
                .param(interview).query(Integer.class).list();
        int followUps = jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = ? AND parent_question_id IS NOT NULL")
                .param(interview).query(Integer.class).single();
        List<Double> answerScores = jdbc.sql("""
                SELECT e.overall_score::float8 FROM evaluations e JOIN answers a ON a.id = e.answer_id JOIN questions q ON q.id = a.question_id
                WHERE a.interview_id = ? ORDER BY q.sequence_no""").param(interview).query(Double.class).list();
        int unsettled = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = ? AND NOT skipped AND evaluation_status <> 'COMPLETED'")
                .param(interview).query(Integer.class).single();
        int questions = jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = ?").param(interview).query(Integer.class).single();
        return new Outcome(persona, interview, report.path("overallScore").isNumber() ? report.get("overallScore").asDouble() : null,
                report.path("recommendation").isString() ? report.get("recommendation").asString() : null,
                report.path("status").asString("?"), questions, difficulties, followUps, answerScores, unsettled, turnMillis, nextFailures,
                session.path("endReason").asString(""));
    }

    private List<String> keyPoints(UUID questionId) {
        return jdbc.sql("SELECT point ->> 'text' FROM questions, jsonb_array_elements(expected_key_points) AS point WHERE id = ?")
                .param(questionId).query(String.class).list();
    }

    // ---- the report ------------------------------------------------------------------------------------------------

    private String report(List<Outcome> outcomes, List<UUID> ids, Duration took) {
        StringBuilder md = new StringBuilder("# Whole-interview evaluation: ollama ").append(MODEL).append("\n\n");
        md.append("Generated by `CandidatePersonaLiveIT`: three simulated candidates of known quality take a 30-minute TECHNICAL ")
                .append("interview (Java, PostgreSQL, mid level) through the HTTP API, with the `local-ollama` profile (").append(MODEL)
                .append(", embeddings ").append(EMBEDDING_MODEL).append(", the curated knowledge base). Run ")
                .append(Instant.now().toString(), 0, 16).append(" UTC, ").append(took.toMinutes()).append(" min.\n\n");

        md.append("## Outcome per candidate\n\n| Candidate | Score | Recommendation | Questions (follow-ups) | Difficulty sequence | Answer scores | Unevaluated | Turn latency p50 / max |\n|---|---|---|---|---|---|---|---|\n");
        for (Outcome outcome : outcomes) {
            List<Long> sorted = outcome.turnMillis().stream().sorted().toList();
            md.append("| ").append(outcome.persona()).append(" | ").append(outcome.score() == null ? "none" : fmt(outcome.score()))
                    .append(" | ").append(outcome.recommendation() == null ? "none" : outcome.recommendation())
                    .append(" | ").append(outcome.questions()).append(" (").append(outcome.followUps()).append(")")
                    .append(" | ").append(outcome.difficulties())
                    .append(" | ").append(outcome.answerScores().stream().map(CandidatePersonaLiveIT::fmt).toList())
                    .append(" | ").append(outcome.pendingOrUnassessable())
                    .append(" | ").append(sorted.isEmpty() ? "-" : seconds(sorted.get(sorted.size() / 2)) + " / " + seconds(sorted.getLast()))
                    .append(" |\n");
        }
        md.append("\nMean rating per criterion (0 to 4, after the server's rules, introduction excluded):\n\n| Candidate | ");
        List<String> criteria = List.of("CORRECTNESS", "COMPLETENESS", "DEPTH", "PRACTICAL_UNDERSTANDING", "REASONING", "COMMUNICATION");
        md.append(String.join(" | ", criteria)).append(" |\n|---|").append("---|".repeat(criteria.size())).append('\n');
        for (Outcome outcome : outcomes) {
            md.append("| ").append(outcome.persona());
            for (String criterion : criteria) {
                Double mean = jdbc.sql("""
                        SELECT avg((score ->> 'rating')::int)::float8
                        FROM evaluations e JOIN answers a ON a.id = e.answer_id JOIN questions q ON q.id = a.question_id,
                             jsonb_array_elements(e.criterion_scores) AS score
                        WHERE a.interview_id = ? AND q.kind <> 'INTRODUCTION' AND score ->> 'criterion' = ?""")
                        .param(outcome.interview()).param(criterion).query(Double.class).optional().orElse(null);
                md.append(" | ").append(mean == null ? "-" : fmt(mean));
            }
            md.append(" |\n");
        }
        boolean ordered = outcomes.get(0).score() != null && outcomes.get(1).score() != null && outcomes.get(2).score() != null
                && outcomes.get(0).score() > outcomes.get(1).score() && outcomes.get(1).score() > outcomes.get(2).score();
        md.append("\nTold apart in the right order (excellent > average > weak): **").append(ordered ? "yes" : "no").append("**.\n");

        String in = "interview_id IN (" + String.join(",", ids.stream().map(id -> "'" + id + "'").toList()) + ")";

        md.append("\n## Structured output and model failures\n\nEvery model call of the three interviews (`llm_calls`). A structured ")
                .append("operation makes a first call, may repair once, and retries transport failures.\n\n")
                .append("Model latency per call is not shown: the test runs on a frozen clock, and the application times its calls with that ")
                .append("clock. The turn latency above is measured on the wall clock (an answer's evaluation plus the next question).\n\n")
                .append("| Task | Calls | First try valid | Repaired | Invalid output | Timeout | Error |\n|---|---|---|---|---|---|---|\n");
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT task,
                       count(*) AS calls,
                       count(*) FILTER (WHERE status = 'OK') AS ok,
                       count(*) FILTER (WHERE attempt = 1 AND status IN ('OK', 'INVALID_OUTPUT')) AS firsts,
                       count(*) FILTER (WHERE attempt = 1 AND status = 'OK') AS first_ok,
                       count(*) FILTER (WHERE status = 'REPAIRED') AS repaired,
                       count(*) FILTER (WHERE status = 'INVALID_OUTPUT') AS invalid,
                       count(*) FILTER (WHERE status = 'TIMEOUT') AS timeouts,
                       count(*) FILTER (WHERE status IN ('ERROR', 'RATE_LIMITED', 'BLOCKED')) AS errors
                FROM llm_calls""" + " WHERE " + in + " GROUP BY task ORDER BY task").query().listOfRows();
        long allFirsts = 0;
        long allFirstOk = 0;
        long allFailures = 0;
        long allCalls = 0;
        for (Map<String, Object> row : rows) {
            long firsts = ((Number) row.get("firsts")).longValue();
            long firstOk = ((Number) row.get("first_ok")).longValue();
            allFirsts += firsts;
            allFirstOk += firstOk;
            allFailures += ((Number) row.get("timeouts")).longValue() + ((Number) row.get("errors")).longValue();
            allCalls += ((Number) row.get("calls")).longValue();
            md.append("| ").append(row.get("task")).append(" | ").append(row.get("calls"))
                    .append(" | ").append(firstOk).append('/').append(firsts).append(" (").append(percent(firstOk, firsts)).append(')')
                    .append(" | ").append(row.get("repaired")).append(" | ").append(row.get("invalid"))
                    .append(" | ").append(row.get("timeouts")).append(" | ").append(row.get("errors")).append(" |\n");
        }
        md.append("\nOverall: ").append(percent(allFirstOk, allFirsts)).append(" of structured operations valid on the first try; ")
                .append(allFailures).append(" of ").append(allCalls).append(" calls failed at the transport level (timeout or error); ")
                .append(outcomes.stream().mapToInt(Outcome::nextQuestionFailures).sum())
                .append(" next-question requests answered 503 and were asked again.\n");

        md.append("\n## Duplicate questions\n\n");
        int asked = jdbc.sql("SELECT count(*) FROM questions WHERE " + in + " AND kind <> 'INTRODUCTION'").query(Integer.class).single();
        int exact = jdbc.sql("""
                SELECT count(*) FROM questions a JOIN questions b ON a.interview_id = b.interview_id AND a.id < b.id
                WHERE a.""" + in + " AND lower(a.prompt) = lower(b.prompt)").query(Integer.class).single();
        List<Map<String, Object>> similar = jdbc.sql("""
                SELECT a.interview_id, max(1 - (a.embedding <=> b.embedding)) AS top,
                       count(*) FILTER (WHERE 1 - (a.embedding <=> b.embedding) >= ?) AS near
                FROM questions a JOIN questions b ON a.interview_id = b.interview_id AND a.id < b.id
                WHERE a.""" + in + " AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL GROUP BY a.interview_id")
                .param(DUPLICATE_SIMILARITY).query().listOfRows();
        long near = similar.stream().mapToLong(row -> ((Number) row.get("near")).longValue()).sum();
        double top = similar.stream().mapToDouble(row -> ((Number) row.get("top")).doubleValue()).max().orElse(0);
        int refused = jdbc.sql("SELECT count(*) FROM llm_calls WHERE " + in + " AND status = 'INVALID_OUTPUT' AND error_message LIKE '%already asked%'")
                .query(Integer.class).single();
        int generated = jdbc.sql("SELECT count(*) FROM llm_calls WHERE " + in + " AND task IN ('QUESTION_GENERATION', 'FOLLOW_UP_GENERATION')")
                .query(Integer.class).single();
        md.append("- Questions asked (introduction excluded): ").append(asked).append('\n')
                .append("- Exact repeats within an interview: ").append(exact).append('\n')
                .append("- Pairs at or above the near-duplicate similarity ").append(DUPLICATE_SIMILARITY).append(": ").append(near)
                .append(" (highest pair: ").append(fmt(top)).append(")\n")
                .append("- Drafts the de-duplication refused (sent back for another question): ").append(refused).append(" of ")
                .append(generated).append(" generation calls\n");

        md.append("\n## Relevance of the cited passages\n\nA generated question's key points cite passages of the knowledge base ")
                .append("(`question_sources`). A citation is counted relevant when its document is about the question's technology, ")
                .append("and on topic when its chunk is tagged with the question's topic.\n\n");
        Map<String, Object> relevance = jdbc.sql("""
                SELECT count(DISTINCT q.id) FILTER (WHERE s.chunk_id IS NOT NULL) AS grounded,
                       count(DISTINCT q.id) AS questions,
                       count(s.chunk_id) AS citations,
                       count(s.chunk_id) FILTER (WHERE d.skill_id = t.skill_id) AS same_skill,
                       count(s.chunk_id) FILTER (WHERE c.topic_id = q.topic_id) AS same_topic
                FROM questions q
                LEFT JOIN topics t ON t.id = q.topic_id
                LEFT JOIN question_sources s ON s.question_id = q.id
                LEFT JOIN knowledge_chunks c ON c.id = s.chunk_id
                LEFT JOIN knowledge_documents d ON d.id = c.document_id
                WHERE q.""" + in + " AND q.kind <> 'INTRODUCTION'").query().singleRow();
        long citations = ((Number) relevance.get("citations")).longValue();
        md.append("- Questions grounded in at least one passage: ").append(relevance.get("grounded")).append(" of ")
                .append(relevance.get("questions")).append('\n')
                .append("- Citations: ").append(citations).append("; same technology ")
                .append(percent(((Number) relevance.get("same_skill")).longValue(), citations)).append(", same topic ")
                .append(percent(((Number) relevance.get("same_topic")).longValue(), citations)).append('\n');
        md.append("\nThe labelled retrieval metrics (recall@5, MRR, abstention) come from `RetrievalQualityLiveIT` on ")
                .append("`ai-evals/datasets/retrieval.jsonl`.\n");
        return md.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / whole);
    }
}
