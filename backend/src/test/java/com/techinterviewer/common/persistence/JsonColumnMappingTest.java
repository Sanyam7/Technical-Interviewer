package com.techinterviewer.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON that goes into the {@code jsonb} columns: produced by the same mapper Hibernate uses, compared with the
 * documents of DATABASE_SCHEMA.md section 5, round-tripped, and checked for what happens on bad input.
 */
class JsonColumnMappingTest {

    private static final JsonMapper APPLICATION = TestJson.applicationMapper();
    /** The mapper Hibernate is configured with (see JsonColumnConfig), exercised directly. */
    private static final Jackson3JsonFormatMapper HIBERNATE =
            (Jackson3JsonFormatMapper) new JsonColumnConfig().hibernateJsonFormatMapper(APPLICATION);

    private static final UUID ANSWER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0e");
    private static final UUID QUESTION = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0f");
    private static final UUID CHUNK = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d10");

    private static String json(Object value) {
        return HIBERNATE.toString(value, value.getClass());
    }

    private static <T> T read(String json, Type type) {
        return HIBERNATE.fromString(json, type);
    }

    private static <T> T roundTrip(T value) {
        return read(json(value), value.getClass());
    }

    private static void assertShape(String expectedJson, String actualJson) {
        JsonNode expected = APPLICATION.readTree(expectedJson);
        JsonNode actual = APPLICATION.readTree(actualJson);
        assertThat(TestJson.equivalent(expected, actual))
                .as("expected %s but was %s", expected, actual)
                .isTrue();
    }

    private static InterviewPlan documentedPlan() {
        return new InterviewPlan(
                1,
                List.of(
                        new SectionPlan(Section.INTRODUCTION, 4, 1),
                        new SectionPlan(Section.QUESTIONING, 24, 6, Map.of(
                                "java/concurrency", 2, "spring-boot/transactions", 2, "postgresql/indexing", 2)),
                        new SectionPlan(Section.CODING, 16, 1),
                        new SectionPlan(Section.SYSTEM_DESIGN, 14, 1)),
                new DifficultyBand(3, 2, 4),
                2);
    }

    // ---- shapes of DATABASE_SCHEMA.md section 5 ----------------------------------------------------------------

    @Test
    void planHasTheDocumentedShape() {
        assertShape("""
                {
                  "version": 1,
                  "sections": [
                    {"section": "INTRODUCTION",  "minutes": 4,  "slots": 1},
                    {"section": "QUESTIONING",   "minutes": 24, "slots": 6, "topicQuotas": {"java/concurrency": 2, "spring-boot/transactions": 2, "postgresql/indexing": 2}},
                    {"section": "CODING",        "minutes": 16, "slots": 1},
                    {"section": "SYSTEM_DESIGN", "minutes": 14, "slots": 1}
                  ],
                  "difficulty": {"start": 3, "min": 2, "max": 4},
                  "maxFollowUpDepth": 2,
                  "maxDesignProbes": 4
                }""", json(documentedPlan()));
    }

    @Test
    void memoryHasTheDocumentedShape() {
        Map<String, TopicStat> topics = new LinkedHashMap<>();
        topics.put("java/concurrency", new TopicStat(2, 71.5, 3, 78));
        InterviewMemory memory = new InterviewMemory(
                topics,
                List.of("java/concurrency:happens-before"),
                List.of(new Claim("Migrated a monolith to Kafka-based events", false, ANSWER)),
                List.of(new AnswerSummary(QUESTION, "java/concurrency", "Explained volatile and happens-before.", 78)),
                Map.of("INJECTION_SUSPECTED", 0),
                Map.of(Section.QUESTIONING, new SectionProgress(3, Instant.parse("2026-10-05T14:03:00Z"))),
                null);

        assertShape("""
                {
                  "topicStats": {"java/concurrency": {"asked": 2, "ema": 71.5, "level": 3, "lastScore": 78}},
                  "demonstratedKeyPoints": ["java/concurrency:happens-before"],
                  "candidateClaims": [{"text": "Migrated a monolith to Kafka-based events", "probed": false, "sourceAnswerId": "%s"}],
                  "answerSummaries": [{"questionId": "%s", "topic": "java/concurrency", "summary": "Explained volatile and happens-before.", "score": 78}],
                  "flags": {"INJECTION_SUSPECTED": 0},
                  "sectionProgress": {"QUESTIONING": {"slotsUsed": 3, "startedAt": "2026-10-05T14:03:00Z"}}
                }""".formatted(ANSWER, QUESTION), json(memory));
    }

    @Test
    void emptyMemoryIsTheColumnDefaultAndReadsBack() {
        // interviews.memory DEFAULT '{}'::jsonb: a row that never had its memory written must load as an empty memory.
        assertThat((InterviewMemory) read("{}", InterviewMemory.class)).isEqualTo(InterviewMemory.empty());
        assertThat(APPLICATION.readTree(json(InterviewMemory.empty())).has("pendingStep")).isFalse();
    }

    @Test
    void absentOptionalMembersAreOmittedNotWrittenAsNull() {
        JsonNode plan = APPLICATION.readTree(json(documentedPlan()));

        assertThat(plan.get("sections").get(0).has("topicQuotas")).as("empty quotas are omitted").isFalse();
        assertThat(plan.get("sections").get(1).has("topicQuotas")).isTrue();
        assertThat(APPLICATION.readTree(json(PendingStep.nextTopic(Section.QUESTIONING, "java/jvm", 3))).has("parentQuestionId")).isFalse();
    }

    @Test
    void instantsAreIso8601StringsAndEnumKeysAreNames() {
        InterviewMemory memory = InterviewMemory.empty().withPendingStep(null);
        InterviewMemory withProgress = new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(),
                Map.of(Section.CODING, new SectionProgress(1, Instant.parse("2026-10-05T14:03:00.250Z")),
                        Section.INTRODUCTION, new SectionProgress(1, Instant.parse("2026-10-05T14:00:00Z"))), null);

        JsonNode node = APPLICATION.readTree(json(withProgress));

        assertThat(node.get("sectionProgress").get("CODING").get("startedAt").asString()).isEqualTo("2026-10-05T14:03:00.250Z");
        assertThat(node.get("sectionProgress").propertyNames()).containsExactly("INTRODUCTION", "CODING");
        assertThat(memory).isEqualTo(InterviewMemory.empty());
    }

    // ---- round trips ------------------------------------------------------------------------------------------------

    @Test
    void planRoundTrips() {
        assertThat(roundTrip(documentedPlan())).isEqualTo(documentedPlan());
    }

    @Test
    void fullMemoryRoundTripsIncludingTheFollowUpPendingStep() {
        PendingStep pending = PendingStep.followUp(
                Section.QUESTIONING, "java/concurrency", 4, QUESTION, FollowUpMode.PROBE_GAP, "kp2", null);
        InterviewMemory memory = new InterviewMemory(
                Map.of("java/concurrency", new TopicStat(2, 71.25, 3, 78.5)),
                List.of("java/concurrency:happens-before"),
                List.of(new Claim("Used CompletableFuture in production", true, ANSWER)),
                List.of(new AnswerSummary(QUESTION, null, "Introduced themselves.", 61.5)),
                Map.of("INJECTION_SUSPECTED", 1, "OFF_TOPIC", 2),
                Map.of(Section.QUESTIONING, new SectionProgress(3, Instant.parse("2026-10-05T14:03:00Z"))),
                pending);

        InterviewMemory restored = roundTrip(memory);

        assertThat(restored).isEqualTo(memory);
        assertThat(restored.pendingStep()).isEqualTo(pending);
        assertThat(restored.awaitingNextQuestion()).isTrue();
    }

    @Test
    void everyPendingStepVariantRoundTrips() {
        List<PendingStep> steps = List.of(
                PendingStep.nextTopic(Section.QUESTIONING, "spring-boot/transactions", 2),
                PendingStep.nextTopic(Section.CODING, null, 5),
                PendingStep.followUp(Section.CODING, null, 3, QUESTION, FollowUpMode.DEEPEN, null, null),
                PendingStep.followUp(Section.SYSTEM_DESIGN, "x/y", 3, QUESTION, FollowUpMode.PROBE_CLAIM, null, "Built a sharded store"),
                PendingStep.followUp(Section.QUESTIONING, "x/y", 1, QUESTION, FollowUpMode.PROBE_GAP, "kp1", null));

        steps.forEach(step -> assertThat(roundTrip(step)).isEqualTo(step));
    }

    @Test
    void questionColumnsRoundTrip() {
        List<ExpectedKeyPoint> keyPoints = List.of(
                new ExpectedKeyPoint("kp1", "volatile guarantees visibility, not atomicity", List.of(CHUNK)),
                new ExpectedKeyPoint("kp2", "happens-before orders writes before reads", List.of()));
        QuestionDetails details = new QuestionDetails(
                List.of("1 <= n <= 100000"),
                List.of(new CodingExample("[1,2,3]", "6", null), new CodingExample("[]", "0", "empty input")),
                "int sum(int[] values)",
                "JAVA");

        String keyPointsJson = HIBERNATE.toString(keyPoints, new TypeRef<List<ExpectedKeyPoint>>() { }.type());
        List<ExpectedKeyPoint> restoredKeyPoints = read(keyPointsJson, new TypeRef<List<ExpectedKeyPoint>>() { }.type());

        assertThat(restoredKeyPoints).isEqualTo(keyPoints);
        assertShape("""
                [{"id":"kp1","text":"volatile guarantees visibility, not atomicity","sourceChunkIds":["%s"]},
                 {"id":"kp2","text":"happens-before orders writes before reads","sourceChunkIds":[]}]""".formatted(CHUNK), keyPointsJson);
        assertThat(roundTrip(details)).isEqualTo(details);
    }

    @Test
    void evaluationColumnsRoundTrip() {
        List<CriterionScore> scores = List.of(
                new CriterionScore(Criterion.CORRECTNESS, 3, 0.4, "Mostly right."),
                new CriterionScore(Criterion.RELIABILITY_AND_TRADEOFFS, 0, 0.1, ""));
        List<KeyPointAssessment> keyPoints = List.of(
                new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "volatile only guarantees visibility", true),
                new KeyPointAssessment("kp2", KeyPointStatus.MISSING, null, false));
        List<FactualError> errors = List.of(new FactualError("HashMap is thread-safe", "It is not.", List.of(CHUNK)));

        Type scoresType = new TypeRef<List<CriterionScore>>() { }.type();
        Type keyPointType = new TypeRef<List<KeyPointAssessment>>() { }.type();
        Type errorType = new TypeRef<List<FactualError>>() { }.type();

        assertThat((Object) read(HIBERNATE.toString(scores, scoresType), scoresType)).isEqualTo(scores);
        assertThat((Object) read(HIBERNATE.toString(keyPoints, keyPointType), keyPointType)).isEqualTo(keyPoints);
        assertThat((Object) read(HIBERNATE.toString(errors, errorType), errorType)).isEqualTo(errors);
        assertShape("""
                [{"keyPointId":"kp1","status":"COVERED","evidenceQuote":"volatile only guarantees visibility","verified":true},
                 {"keyPointId":"kp2","status":"MISSING","evidenceQuote":null,"verified":false}]""",
                HIBERNATE.toString(keyPoints, keyPointType));
        assertThat(HIBERNATE.toString(scores, scoresType)).contains("\"criterion\":\"CORRECTNESS\"", "\"rating\":3", "\"weight\":0.4");
    }

    @Test
    void aLoweredRatingAndALinkedMistakeAddMembersThatOlderRowsDoNotHave() {
        Type scoresType = new TypeRef<List<CriterionScore>>() { }.type();
        Type errorType = new TypeRef<List<FactualError>>() { }.type();
        List<CriterionScore> capped = List.of(new CriterionScore(Criterion.CORRECTNESS, 2, 0.3, "Two errors.", 4, "FACTUAL_ERRORS"));
        List<FactualError> linked = List.of(new FactualError("HashMap needs no lock", "It does.", List.of(), "HashMap is thread-safe"));

        assertThat((Object) read(HIBERNATE.toString(capped, scoresType), scoresType)).isEqualTo(capped);
        assertThat((Object) read(HIBERNATE.toString(linked, errorType), errorType)).isEqualTo(linked);
        assertThat(HIBERNATE.toString(capped, scoresType)).contains("\"modelRating\":4", "\"cappedBy\":\"FACTUAL_ERRORS\"");
        // As the rows written before: no member for what is absent, and those rows read back with nulls.
        assertThat(HIBERNATE.toString(List.of(new CriterionScore(Criterion.DEPTH, 3, 0.2, "")), scoresType))
                .doesNotContain("modelRating").doesNotContain("cappedBy");
        assertThat(HIBERNATE.toString(List.of(new FactualError("a", "b", List.of())), errorType)).doesNotContain("repeats");
        FactualError older = read("{\"claim\":\"a\",\"correction\":\"b\",\"sourceChunkIds\":[]}", FactualError.class);
        assertThat(older.repeats()).isNull();
    }

    @Test
    void theKnowledgeGapsOfAReportRoundTrip() {
        var topic = new com.techinterviewer.evaluation.domain.KnowledgeGapReport.GapTopic("java/concurrency", "Java / Concurrency");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var gaps = new com.techinterviewer.evaluation.domain.KnowledgeGapReport(
                List.of(new com.techinterviewer.evaluation.domain.KnowledgeGapReport.MissedConcept("count++ is not atomic", KeyPointStatus.MISSING, topic, first)),
                List.of(new com.techinterviewer.evaluation.domain.KnowledgeGapReport.MisconceptionFinding("volatile locks", "it does not", null, second)),
                List.of(new com.techinterviewer.evaluation.domain.KnowledgeGapReport.WeakTopic(topic, 31.5, 2)),
                List.of(new com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake("count++ is not atomic",
                        com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake.Kind.MISSED_CONCEPT,
                        java.util.Set.of(com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake.Basis.SHARED_SOURCE),
                        List.of(first, second), List.of(topic))));

        assertThat(roundTrip(gaps)).isEqualTo(gaps);
        assertThat(HIBERNATE.toString(gaps, com.techinterviewer.evaluation.domain.KnowledgeGapReport.class))
                .contains("\"missedConcepts\":[", "\"weakTopics\":[", "\"basis\":[\"SHARED_SOURCE\"]");
    }

    // ---- reading what the database holds -------------------------------------------------------------------------------

    @Test
    void storedDocumentsMayCarryPropertiesThisVersionDoesNotKnow() {
        String fromANewerVersion = """
                {"version":1,"sections":[{"section":"INTRODUCTION","minutes":4,"slots":1,"futureField":true}],
                 "difficulty":{"start":3,"min":2,"max":4,"bias":0.5},"maxFollowUpDepth":2,"addedLater":{"x":1}}""";

        InterviewPlan plan = read(fromANewerVersion, InterviewPlan.class);

        assertThat(plan.sections()).hasSize(1);
        assertThat(plan.difficulty()).isEqualTo(new DifficultyBand(3, 2, 4));
        assertThat(plan.maxDesignProbes()).as("a plan stored before design probes existed allows none").isZero();
        // The HTTP mapper stays strict: the same document is not acceptable as a request body.
        assertThatThrownBy(() -> APPLICATION.readValue(fromANewerVersion, InterviewPlan.class))
                .hasMessageContaining("futureField");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"version\":1,\"sections\":[],\"difficulty\":{\"start\":3,\"min\":2,\"max\":4},\"maxFollowUpDepth\":2}",
        "{\"version\":1,\"sections\":[{\"section\":\"CODING\",\"minutes\":4,\"slots\":1},{\"section\":\"QUESTIONING\",\"minutes\":4,\"slots\":1}],"
                + "\"difficulty\":{\"start\":3,\"min\":2,\"max\":4},\"maxFollowUpDepth\":2}",
        "{\"version\":1,\"sections\":[{\"section\":\"CODING\",\"minutes\":4,\"slots\":1}],\"difficulty\":{\"start\":9,\"min\":2,\"max\":4},\"maxFollowUpDepth\":2}",
        "{\"version\":1,\"sections\":[{\"section\":\"CODING\",\"minutes\":4,\"slots\":1}],\"difficulty\":{\"start\":3,\"min\":2,\"max\":4},\"maxFollowUpDepth\":3}",
        "{\"version\":1,\"sections\":[{\"section\":\"NOT_A_SECTION\",\"minutes\":4,\"slots\":1}],\"difficulty\":{\"start\":3,\"min\":2,\"max\":4},\"maxFollowUpDepth\":2}",
        "{}"
    })
    void aStoredPlanThatBreaksAnInvariantIsRefusedInsteadOfLoadedHalfValid(String corrupt) {
        assertThatThrownBy(() -> HIBERNATE.fromString(corrupt, InterviewPlan.class)).isNotNull();
    }

    @Test
    void impossibleRatingsAndLevelsAreRefusedOnRead() {
        assertThatThrownBy(() -> HIBERNATE.fromString(
                "{\"criterion\":\"CORRECTNESS\",\"rating\":9,\"weight\":0.4,\"rationale\":\"x\"}", CriterionScore.class))
                .rootCause().hasMessageContaining("rating must be between 0 and 4");
        assertThatThrownBy(() -> HIBERNATE.fromString("{\"asked\":1,\"ema\":50,\"level\":0,\"lastScore\":50}", TopicStat.class))
                .rootCause().hasMessageContaining("level must be between 1 and 5");
        assertThatThrownBy(() -> HIBERNATE.fromString("{\"id\":\" \",\"text\":\"x\",\"sourceChunkIds\":[]}", ExpectedKeyPoint.class))
                .rootCause().hasMessageContaining("id must not be blank");
    }

    /** Captures a generic type for the mapper. */
    abstract static class TypeRef<T> {
        Type type() {
            return ((java.lang.reflect.ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }
}
