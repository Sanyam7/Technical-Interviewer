package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.ReportAggregator;
import com.techinterviewer.evaluation.ReportAnswer;
import com.techinterviewer.evaluation.ReportScores;
import com.techinterviewer.evaluation.ScoreAggregator;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What is true of every interview at every moment, read from the database after every step: the transition log only takes
 * edges of the state machine, questions are numbered without a gap, each is answered at most once and only the last one can
 * be open, follow-ups stay within their depth and the slots of their section, no answer was accepted later than the grace
 * period after the deadline, and every stored score is what the rubric makes of the stored ratings.
 */
public final class TurnInvariants {

    /** The edges of ARCHITECTURE.md section 5, plus the creation edge and the ones the engine adds (see its javadoc). */
    public static final Set<String> LEGAL_EDGES = Set.of(
            "-:INIT:CREATE",
            "INIT:INTRODUCTION:START", "INIT:ABANDONED:ABANDON",
            "INTRODUCTION:QUESTIONING:INTRO_ANSWERED", "INTRODUCTION:CODING:INTRO_ANSWERED", "INTRODUCTION:SYSTEM_DESIGN:INTRO_ANSWERED",
            "QUESTIONING:FOLLOW_UP:ANSWER_EVALUATED", "QUESTIONING:QUESTIONING:ANSWER_EVALUATED",
            "QUESTIONING:CODING:SECTION_COMPLETE", "QUESTIONING:SYSTEM_DESIGN:SECTION_COMPLETE", "QUESTIONING:FINAL_EVALUATION:SECTION_COMPLETE",
            "FOLLOW_UP:FOLLOW_UP:ANSWER_EVALUATED", "FOLLOW_UP:QUESTIONING:FOLLOW_UP_DONE", "FOLLOW_UP:CODING:FOLLOW_UP_DONE",
            "FOLLOW_UP:SYSTEM_DESIGN:FOLLOW_UP_DONE",
            "CODING:FOLLOW_UP:ANSWER_EVALUATED", "CODING:CODING:ANSWER_EVALUATED", "CODING:SYSTEM_DESIGN:SECTION_COMPLETE",
            "CODING:FINAL_EVALUATION:SECTION_COMPLETE",
            "SYSTEM_DESIGN:FOLLOW_UP:ANSWER_EVALUATED", "SYSTEM_DESIGN:FINAL_EVALUATION:SECTION_COMPLETE",
            "INTRODUCTION:FINAL_EVALUATION:TIME_UP", "QUESTIONING:FINAL_EVALUATION:TIME_UP", "FOLLOW_UP:FINAL_EVALUATION:TIME_UP",
            "CODING:FINAL_EVALUATION:TIME_UP", "SYSTEM_DESIGN:FINAL_EVALUATION:TIME_UP",
            "INTRODUCTION:FINAL_EVALUATION:END_REQUESTED", "QUESTIONING:FINAL_EVALUATION:END_REQUESTED",
            "FOLLOW_UP:FINAL_EVALUATION:END_REQUESTED", "CODING:FINAL_EVALUATION:END_REQUESTED",
            "SYSTEM_DESIGN:FINAL_EVALUATION:END_REQUESTED",
            "FINAL_EVALUATION:COMPLETED:REPORT_READY", "FINAL_EVALUATION:FINAL_EVALUATION:REPORT_FAILED");

    private static final Set<String> ACCEPTING = Set.of("INTRODUCTION", "QUESTIONING", "FOLLOW_UP", "CODING", "SYSTEM_DESIGN");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EvaluationRepository evaluations;
    private final Duration grace;

    public TurnInvariants(JdbcClient jdbc, JsonMapper json, EvaluationRepository evaluations, Duration grace) {
        this.jdbc = jdbc;
        this.json = json;
        this.evaluations = evaluations;
        this.grace = grace;
    }

    /** Every database invariant of the interview, as of now. */
    public void check(UUID interview) {
        checkTransitionLog(interview);
        JsonNode plan = json.readTree(jdbc.sql("SELECT plan::text FROM interviews WHERE id = :id").param("id", interview).query(String.class).single());
        List<Question> questions = checkQuestions(interview, plan);
        checkInterviewRow(interview, plan, questions);
        checkAnswers(interview, questions);
        checkEvaluations(interview);
    }

    public void checkTransitionLog(UUID interview) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT from_state, to_state, event, occurred_at FROM interview_state_transitions WHERE interview_id = :id ORDER BY id")
                .param("id", interview).query().listOfRows();
        assertThat(rows).as("the log starts with the creation of the interview").isNotEmpty();
        String previousTo = null;
        Instant previousAt = null;
        for (Map<String, Object> row : rows) {
            String from = row.get("from_state") == null ? "-" : (String) row.get("from_state");
            String edge = from + ":" + row.get("to_state") + ":" + row.get("event");
            assertThat(LEGAL_EDGES).as("edge %s of the transition log", edge).contains(edge);
            if (previousTo == null) {
                assertThat(edge).isEqualTo("-:INIT:CREATE");
            } else {
                assertThat(from).as("the transition %s leaves the state the previous one entered", edge).isEqualTo(previousTo);
            }
            Instant at = ((Timestamp) row.get("occurred_at")).toInstant();
            if (previousAt != null) {
                assertThat(at).as("transition %s happened before the one that precedes it", edge).isAfterOrEqualTo(previousAt);
            }
            previousTo = (String) row.get("to_state");
            previousAt = at;
        }
    }

    private record Question(UUID id, int sequenceNo, String kind, String section, int depth, UUID parent) {
    }

    private List<Question> checkQuestions(UUID interview, JsonNode plan) {
        List<Question> questions = jdbc.sql("SELECT id, sequence_no, kind, section, followup_depth, parent_question_id FROM questions WHERE interview_id = :id ORDER BY sequence_no")
                .param("id", interview)
                .query((rs, n) -> new Question(rs.getObject("id", UUID.class), rs.getInt("sequence_no"), rs.getString("kind"), rs.getString("section"),
                        rs.getInt("followup_depth"), rs.getObject("parent_question_id", UUID.class)))
                .list();
        Map<UUID, Question> byId = new HashMap<>();
        Map<String, Integer> roots = new HashMap<>();
        int maxDepth = plan.get("maxFollowUpDepth").asInt();
        int maxDesignProbes = plan.path("maxDesignProbes").asInt(0);
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            assertThat(question.sequenceNo()).as("sequence numbers have no gap").isEqualTo(i + 1);
            byId.put(question.id(), question);
            if (question.kind().equals("FOLLOW_UP")) {
                Question parent = byId.get(question.parent());
                assertThat(parent).as("the parent of follow-up %d was asked before it", question.sequenceNo()).isNotNull();
                assertThat(question.depth()).isEqualTo(parent.depth() + 1)
                        .isBetween(1, question.section().equals("SYSTEM_DESIGN") ? maxDesignProbes : maxDepth);
                assertThat(question.section()).as("a follow-up stays in the section of its thread").isEqualTo(parent.section());
            } else {
                assertThat(question.depth()).isZero();
                assertThat(question.parent()).isNull();
                roots.merge(question.section(), 1, Integer::sum);
            }
        }
        assertThat(questions).isNotEmpty();
        assertThat(questions.getFirst().kind()).as("the introduction is always the first question").isEqualTo("INTRO");
        for (JsonNode planned : plan.get("sections")) {
            String section = planned.get("section").asString();
            assertThat(roots.getOrDefault(section, 0)).as("main questions asked in %s", section).isLessThanOrEqualTo(planned.get("slots").asInt());
        }
        assertThat(roots.keySet()).as("only sections of the plan were asked").isSubsetOf(planned(plan));
        return questions;
    }

    private static Set<String> planned(JsonNode plan) {
        Set<String> sections = new java.util.HashSet<>();
        plan.get("sections").forEach(section -> sections.add(section.get("section").asString()));
        return sections;
    }

    private void checkInterviewRow(UUID interview, JsonNode plan, List<Question> questions) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT state, resume_state, current_question_id, started_at, deadline_at, ended_at, end_reason,
                               memory -> 'pendingStep' IS NOT NULL AS pending, memory::text AS memory
                        FROM interviews WHERE id = :id""").param("id", interview).query().singleRow();
        String state = (String) row.get("state");
        UUID current = (UUID) row.get("current_question_id");
        boolean pending = (Boolean) row.get("pending");
        Question last = questions.getLast();
        if (ACCEPTING.contains(state)) {
            assertThat(row.get("deadline_at")).as("an interview that asks questions has a deadline").isNotNull();
            assertThat(current != null ^ pending).as("exactly one of an open question and a decided next one in state %s", state).isTrue();
            if (current != null) {
                assertThat(current).as("the open question is the last one asked").isEqualTo(last.id());
                String expected = switch (state) {
                    case "INTRODUCTION" -> "INTRO";
                    case "QUESTIONING" -> "TECHNICAL";
                    case "CODING" -> "CODING";
                    case "SYSTEM_DESIGN" -> "SYSTEM_DESIGN";
                    default -> "FOLLOW_UP";
                };
                assertThat(last.kind()).as("kind of the open question in %s", state).isEqualTo(expected);
            }
            assertThat(state.equals("FOLLOW_UP") == (row.get("resume_state") != null)).as("a resume section exactly in FOLLOW_UP").isTrue();
        } else {
            assertThat(current).as("no open question in state %s", state).isNull();
            assertThat(pending).as("no decided question in state %s", state).isFalse();
            assertThat(row.get("resume_state")).isNull();
        }
        if (state.equals("FINAL_EVALUATION") || state.equals("COMPLETED")) {
            assertThat(row.get("ended_at")).isNotNull();
            assertThat(row.get("end_reason")).isIn("PLAN_COMPLETE", "TIME_UP", "CANDIDATE_ENDED");
        } else if (ACCEPTING.contains(state)) {
            assertThat(row.get("ended_at")).isNull();
            assertThat(row.get("end_reason")).isNull();
        }
        JsonNode memory = json.readTree((String) row.get("memory"));
        for (JsonNode planned : plan.get("sections")) {
            JsonNode progress = memory.path("sectionProgress").path(planned.get("section").asString());
            if (!progress.isMissingNode()) {
                assertThat(progress.get("slotsUsed").asInt()).as("slots used in %s", planned.get("section").asString()).isLessThanOrEqualTo(planned.get("slots").asInt());
            }
        }
    }

    private void checkAnswers(UUID interview, List<Question> questions) {
        Instant deadline = jdbc.sql("SELECT deadline_at FROM interviews WHERE id = :id").param("id", interview).query(Timestamp.class).single().toInstant();
        List<Map<String, Object>> answers = jdbc.sql("SELECT question_id, skipped, content, evaluation_status, submitted_at FROM answers WHERE interview_id = :id")
                .param("id", interview).query().listOfRows();
        Set<UUID> answered = new java.util.HashSet<>();
        for (Map<String, Object> answer : answers) {
            assertThat(answered.add((UUID) answer.get("question_id"))).as("a question is answered at most once").isTrue();
            Instant submitted = ((Timestamp) answer.get("submitted_at")).toInstant();
            assertThat(submitted).as("an accepted answer is never later than the deadline plus the grace period").isBeforeOrEqualTo(deadline.plus(grace));
            if ((Boolean) answer.get("skipped")) {
                assertThat(answer.get("content")).isEqualTo("");
                assertThat(answer.get("evaluation_status")).isEqualTo("COMPLETED");
            }
        }
        for (int i = 0; i < questions.size() - 1; i++) {
            assertThat(answered).as("question %d was followed by another, so it was answered", questions.get(i).sequenceNo()).contains(questions.get(i).id());
        }
        assertThat(answered).as("answers belong to questions of the interview").isSubsetOf(questions.stream().map(Question::id).toList());
        Long evaluated = jdbc.sql("SELECT count(*) FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.interview_id = :id AND a.skipped = false AND a.evaluation_status = 'COMPLETED'")
                .param("id", interview).query(Long.class).single();
        Long completed = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND skipped = false AND evaluation_status = 'COMPLETED'")
                .param("id", interview).query(Long.class).single();
        Long evaluations = jdbc.sql("SELECT count(*) FROM evaluations e JOIN answers a ON a.id = e.answer_id WHERE a.interview_id = :id").param("id", interview).query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM answers a LEFT JOIN evaluations e ON e.answer_id = a.id WHERE a.interview_id = :id AND a.evaluation_status = 'UNASSESSABLE' AND (e.id IS NOT NULL OR a.evaluation_failure IS NULL OR a.evaluation_attempts < 1 OR a.next_evaluation_at IS NOT NULL OR a.skipped)")
                .param("id", interview).query(Long.class).single())
                .as("an unassessable answer was attempted, has a reason, no evaluation and nothing scheduled").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_attempts > 3").param("id", interview).query(Long.class).single())
                .as("no answer is attempted more than three times").isZero();
        assertThat(evaluated).as("a completed answer has its evaluation").isEqualTo(completed);
        assertThat(evaluations).as("an evaluation exists only for an answer that was evaluated, never for a skip").isEqualTo(completed);
    }

    private void checkEvaluations(UUID interview) {
        for (Evaluation evaluation : evaluations.findByInterviewId(interview)) {
            Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
            Map<Criterion, String> rationales = new EnumMap<>(Criterion.class);
            List<com.techinterviewer.evaluation.CriterionWeight> weights = new java.util.ArrayList<>();
            for (CriterionScore score : evaluation.getCriterionScores()) {
                assertThat(score.rating()).isBetween(0, 4);
                if (score.capped()) {
                    assertThat(score.modelRating()).as("a rule only lowers a rating").isGreaterThan(score.rating());
                    assertThat(com.techinterviewer.evaluation.RatingRules.Rule.valueOf(score.cappedBy())).isNotNull();
                }
                ratings.put(score.criterion(), score.rating());
                rationales.put(score.criterion(), score.rationale());
                weights.add(new com.techinterviewer.evaluation.CriterionWeight(score.criterion(), java.math.BigDecimal.valueOf(score.weight())));
            }
            assertThat(ratings.keySet()).as("exactly the criteria of the evaluator")
                    .isEqualTo(com.techinterviewer.evaluation.RubricRegistry.criteria(evaluation.getEvaluator()));
            // The weights are those of the question's skill profile; whatever they were, they add up to one and give the stored score.
            com.techinterviewer.evaluation.Rubric applied = new com.techinterviewer.evaluation.Rubric("stored", evaluation.getEvaluator(), weights);
            double overall = evaluation.getOverallScore().doubleValue();
            assertThat(overall).isBetween(0.0, 100.0);
            assertThat(overall).as("the stored score is what the stored weights make of the stored ratings")
                    .isEqualTo(ScoreAggregator.aggregate(applied, ratings, rationales).overall());
        }
    }

    /** The overall score the report must have: the pure aggregation of the interview's stored answers and evaluations. */
    public @org.jspecify.annotations.Nullable Double expectedOverallScore(UUID interview) {
        List<ReportAnswer> answers = jdbc.sql("""
                        SELECT q.id, q.sequence_no, q.kind, q.section, q.difficulty, a.skipped, a.evaluation_failure, a.evaluation_status, e.overall_score, e.flags::text AS flags,
                               s.slug || '/' || t.slug AS topic,
                               (WITH RECURSIVE up AS (SELECT id, parent_question_id FROM questions WHERE id = q.id
                                                      UNION ALL SELECT p.id, p.parent_question_id FROM questions p JOIN up ON p.id = up.parent_question_id)
                                SELECT id FROM up WHERE parent_question_id IS NULL) AS thread
                        FROM answers a JOIN questions q ON q.id = a.question_id
                        LEFT JOIN evaluations e ON e.answer_id = a.id
                        LEFT JOIN topics t ON t.id = q.topic_id LEFT JOIN skills s ON s.id = t.skill_id
                        WHERE a.interview_id = :id ORDER BY q.sequence_no""")
                .param("id", interview)
                .query((rs, n) -> new ReportAnswer(
                        rs.getObject("id", UUID.class),
                        rs.getObject("thread", UUID.class),
                        rs.getInt("sequence_no"),
                        QuestionKind.valueOf(rs.getString("kind")),
                        Section.valueOf(rs.getString("section")),
                        rs.getString("section").equals("QUESTIONING") ? rs.getString("topic") : null,
                        rs.getInt("difficulty"),
                        rs.getBoolean("skipped"),
                        rs.getBoolean("skipped") || rs.getBigDecimal("overall_score") == null ? null : rs.getBigDecimal("overall_score").doubleValue(),
                        flagsOf(rs.getString("flags"), rs.getBoolean("skipped")),
                        "UNASSESSABLE".equals(rs.getString("evaluation_status"))
                                ? com.techinterviewer.interview.domain.EvaluationFailure.valueOf(rs.getString("evaluation_failure"))
                                : null))
                .list();
        ReportScores scores = ReportAggregator.aggregate(answers);
        return scores.overallScore();
    }

    private static Set<EvaluationFlag> flagsOf(String text, boolean skipped) {
        Set<EvaluationFlag> flags = EnumSet.noneOf(EvaluationFlag.class);
        if (!skipped && text != null) {
            for (EvaluationFlag flag : EvaluationFlag.values()) {
                if (text.contains(flag.name())) {
                    flags.add(flag);
                }
            }
        }
        return flags;
    }

    /** Keeps the list type visible to callers that only want the log. */
    public List<String> log(UUID interview) {
        List<String> rows = new ArrayList<>();
        jdbc.sql("SELECT coalesce(from_state, '-') || ':' || to_state || ':' || event FROM interview_state_transitions WHERE interview_id = :id ORDER BY id")
                .param("id", interview).query(String.class).list().forEach(rows::add);
        return rows;
    }
}
