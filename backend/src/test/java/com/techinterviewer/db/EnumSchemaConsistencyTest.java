package com.techinterviewer.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.coding.domain.RunStatus;
import com.techinterviewer.coding.domain.TestStatus;
import com.techinterviewer.coding.problem.TestCategory;
import com.techinterviewer.catalog.SkillCategory;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.interview.api.dto.AnswerFormat;
import com.techinterviewer.interview.api.dto.FinishReason;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.engine.DeadlineStatus;
import com.techinterviewer.interview.engine.EvaluationOutcome;
import com.techinterviewer.rag.DocumentSourceType;
import com.techinterviewer.rag.DocumentStatus;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Visibility;
import com.techinterviewer.user.Role;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Keeps the Java enums that are stored as text equal to the {@code CHECK (col IN (...))} lists of the Flyway
 * migrations, in both directions: a constant that the database would reject, a value the database allows that
 * Java cannot read, a new CHECK list nobody mapped and a new enum nobody classified all fail the build.
 *
 * <p>The migrations are parsed as text, so the test needs no database and runs with the unit tests.
 */
class EnumSchemaConsistencyTest {

    /** CHECK constraint name to the enum whose constants must equal its value list exactly. */
    private static final Map<String, Class<? extends Enum<?>>> CHECKED = new LinkedHashMap<>();

    static {
        CHECKED.put("ck_users_role", Role.class);
        CHECKED.put("ck_skills_category", SkillCategory.class);
        CHECKED.put("ck_interview_configurations_role", TargetRole.class);
        CHECKED.put("ck_interview_configurations_experience_level", ExperienceLevel.class);
        CHECKED.put("ck_interview_configurations_difficulty", InterviewDifficulty.class);
        CHECKED.put("ck_interview_configurations_type", InterviewType.class);
        CHECKED.put("ck_interviews_state", InterviewState.class);
        CHECKED.put("ck_interviews_end_reason", EndReason.class);
        CHECKED.put("ck_interview_state_transitions_from_state", InterviewState.class);
        CHECKED.put("ck_interview_state_transitions_to_state", InterviewState.class);
        CHECKED.put("ck_questions_section", Section.class);
        CHECKED.put("ck_questions_kind", QuestionKind.class);
        CHECKED.put("ck_questions_source", QuestionOrigin.class);
        CHECKED.put("ck_answers_evaluation_status", EvaluationStatus.class);
        CHECKED.put("ck_answers_evaluation_failure", EvaluationFailure.class);
        CHECKED.put("ck_evaluations_evaluator", Evaluator.class);
        CHECKED.put("ck_reports_status", ReportStatus.class);
        CHECKED.put("ck_reports_outcome", ReportOutcome.class);
        CHECKED.put("ck_reports_recommendation", Recommendation.class);
        CHECKED.put("ck_report_skill_scores_confidence", Confidence.class);
        CHECKED.put("ck_evaluations_evaluator_confidence", Confidence.class);
        CHECKED.put("ck_knowledge_documents_source_type", DocumentSourceType.class);
        CHECKED.put("ck_knowledge_documents_doc_type", DocumentType.class);
        CHECKED.put("ck_knowledge_documents_visibility", Visibility.class);
        CHECKED.put("ck_knowledge_documents_status", DocumentStatus.class);
        CHECKED.put("ck_code_test_runs_status", RunStatus.class);
        CHECKED.put("ck_candidate_resumes_file_type", com.techinterviewer.resume.ResumeFileType.class);
        CHECKED.put("ck_interview_configurations_strategy", com.techinterviewer.interview.domain.InterviewStrategy.class);
        CHECKED.put("ck_questions_question_type", com.techinterviewer.interview.domain.QuestionType.class);
    }

    /** CHECK lists whose values are a subset of an enum (not all of it). */
    private static final Map<String, Set<String>> SUBSETS = Map.of(
            "ck_interviews_resume_state", Set.of("QUESTIONING", "CODING", "SYSTEM_DESIGN"));

    /** CHECK lists that do not constrain a Java enum, and why. */
    private static final Map<String, String> NOT_ENUMS = Map.of(
            "ck_interview_configurations_duration", "minutes: a list of numbers, validated by the configuration DTO",
            "ck_llm_calls_status", "owned by the AI layer, which has no entity yet");

    /** Enums stored as text without a CHECK (JSON documents, a text array, a free varchar), and where. */
    private static final Map<Class<? extends Enum<?>>, String> UNCHECKED = new LinkedHashMap<>();

    static {
        UNCHECKED.put(InterviewEvent.class, "interview_state_transitions.event varchar(30), no check");
        UNCHECKED.put(Criterion.class, "evaluations.criterion_scores jsonb; questions.probe_criterion varchar(30), no check (the names grow)");
        UNCHECKED.put(com.techinterviewer.interview.domain.ArchitectureDiagram.Kind.class, "answers.architecture jsonb");
        UNCHECKED.put(KeyPointStatus.class, "evaluations.key_points jsonb");
        UNCHECKED.put(TestStatus.class, "code_test_runs.outcomes jsonb");
        UNCHECKED.put(TestCategory.class, "code_test_runs.outcomes jsonb");
        UNCHECKED.put(com.techinterviewer.coding.problem.CodeLanguage.class, "code_test_runs.language varchar (its catalog code), no check");
        UNCHECKED.put(com.techinterviewer.coding.problem.ValueType.class, "not stored: the type system of the problem files");
        UNCHECKED.put(com.techinterviewer.coding.problem.Comparison.class, "not stored: read from the problem files");
        UNCHECKED.put(com.techinterviewer.coding.testing.TestRunner.Scope.class, "not stored: which tests a run uses");
        UNCHECKED.put(EvaluationFlag.class, "evaluations.flags text[]");
        UNCHECKED.put(FollowUpMode.class, "interviews.memory jsonb");
        UNCHECKED.put(PendingStep.Kind.class, "interviews.memory jsonb");
        UNCHECKED.put(DeadlineStatus.class, "not stored: result of the engine's deadline check");
        UNCHECKED.put(EvaluationOutcome.Status.class, "not stored: engine input describing what became of an answer");
        UNCHECKED.put(AnswerFormat.class, "not stored: how the API says a question is answered (derived from the question kind)");
        UNCHECKED.put(FinishReason.class, "not stored: why the client calls POST /finish");
        UNCHECKED.put(com.techinterviewer.interview.engine.TopicPerformance.class, "not stored: derived from the memory's moving averages");
        UNCHECKED.put(com.techinterviewer.interview.engine.FollowUpDecision.Reason.class,
                "interview_state_transitions.details jsonb (as text in the rationale)");
        UNCHECKED.put(com.techinterviewer.evaluation.RatingRules.Rule.class, "evaluations.criterion_scores jsonb (cappedBy, by name)");
        UNCHECKED.put(com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake.Kind.class, "reports.knowledge_gaps jsonb");
        UNCHECKED.put(com.techinterviewer.evaluation.domain.KnowledgeGapReport.RepeatedMistake.Basis.class, "reports.knowledge_gaps jsonb");
        UNCHECKED.put(com.techinterviewer.rag.Technology.class, "knowledge_documents.metadata jsonb (its slug)");
        UNCHECKED.put(com.techinterviewer.rag.ingestion.IngestionService.Outcome.class, "not stored: what one ingestion run did to a document");
        UNCHECKED.put(com.techinterviewer.resume.domain.ExperienceKind.class, "candidate_resumes.profile jsonb");
        UNCHECKED.put(com.techinterviewer.resume.domain.YearsOfExperience.Basis.class, "candidate_resumes.profile jsonb");
        UNCHECKED.put(com.techinterviewer.interview.domain.InterviewStage.class, "interviews.plan and interviews.memory jsonb");
        UNCHECKED.put(com.techinterviewer.evaluation.domain.AssessmentArea.class, "reports.assessment_areas jsonb");
    }

    private static final Pattern IN_CHECK = Pattern.compile(
            "CONSTRAINT\\s+(ck_\\w+)\\s+CHECK\\s*\\(\\s*(\\w+)\\s+IN\\s*\\(([^)]*)\\)\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /** CHECK constraint name to its allowed values (empty when the list is not made of strings). */
    private static Map<String, Set<String>> checkLists() throws IOException {
        Map<String, Set<String>> lists = new TreeMap<>();
        // In version order, so a later migration that replaces a CHECK constraint wins over the earlier definition.
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");
        Arrays.sort(migrations, java.util.Comparator.comparing(Resource::getFilename));
        for (Resource migration : migrations) {
            String sql = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher check = IN_CHECK.matcher(sql);
            while (check.find()) {
                Set<String> values = new TreeSet<>();
                Matcher value = QUOTED.matcher(check.group(3));
                while (value.find()) {
                    values.add(value.group(1));
                }
                lists.put(check.group(1), values);
            }
        }
        return lists;
    }

    private static Set<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theMigrationsAreFoundAndParsed() throws IOException {
        // Guards the guard: if the regular expression stopped matching, every other test here would pass vacuously.
        assertThat(checkLists()).hasSizeGreaterThanOrEqualTo(CHECKED.size());
        assertThat(checkLists().get("ck_interviews_state")).contains("INIT", "COMPLETED");
    }

    @Test
    void everyCheckedEnumEqualsItsCheckListExactly() throws IOException {
        Map<String, Set<String>> lists = checkLists();

        CHECKED.forEach((constraint, type) -> {
            assertThat(lists).as("constraint %s exists in the migrations", constraint).containsKey(constraint);
            assertThat(names(type)).as("%s constants vs %s", type.getSimpleName(), constraint).isEqualTo(lists.get(constraint));
        });
    }

    @Test
    void subsetChecksOnlyAllowValuesOfTheirEnum() throws IOException {
        Map<String, Set<String>> lists = checkLists();

        SUBSETS.forEach((constraint, expected) -> {
            assertThat(lists.get(constraint)).as(constraint).isEqualTo(new TreeSet<>(expected));
            assertThat(names(InterviewState.class)).as("%s values are interview states", constraint).containsAll(expected);
        });
    }

    @Test
    void everyInListCheckOfTheSchemaIsMappedOrExplicitlyExcluded() throws IOException {
        Set<String> known = new TreeSet<>(CHECKED.keySet());
        known.addAll(SUBSETS.keySet());
        known.addAll(NOT_ENUMS.keySet());

        assertThat(checkLists().keySet())
                .as("a new CHECK ... IN (...) needs an enum mapping (or an entry in NOT_ENUMS with the reason)")
                .isSubsetOf(known);
    }

    @Test
    void everyEnumOfTheDomainPackagesIsClassifiedAsCheckedOrUnchecked() {
        Set<Class<?>> classified = new java.util.HashSet<>(CHECKED.values());
        classified.addAll(UNCHECKED.keySet());

        Set<String> enums = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.techinterviewer.user", "com.techinterviewer.catalog", "com.techinterviewer.interview",
                        "com.techinterviewer.evaluation", "com.techinterviewer.rag", "com.techinterviewer.coding",
                        "com.techinterviewer.design", "com.techinterviewer.resume")
                .stream()
                .filter(JavaClass::isEnum)
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(enums)
                .as("a new enum must be added to CHECKED (with its CHECK constraint) or UNCHECKED (with its storage)")
                .containsExactlyInAnyOrderElementsOf(classified.stream().map(Class::getName).collect(Collectors.toSet()));
    }

    @Test
    void uncheckedEnumsStillFitTheirColumns() {
        // interview_state_transitions.event is varchar(30)
        assertThat(names(InterviewEvent.class)).allSatisfy(name -> assertThat(name.length()).isLessThanOrEqualTo(30));
        // every stored constant is an upper-case identifier, so a text value in the database is never ambiguous
        UNCHECKED.keySet().forEach(type -> assertThat(names(type)).allMatch(name -> name.matches("[A-Z][A-Z_]*")));
    }

    @Test
    void checkedEnumsAreUpperSnakeCaseAndFitTheirVarcharColumns() {
        CHECKED.values().forEach(type -> assertThat(names(type)).allMatch(name -> name.matches("[A-Z][A-Z_]*") && name.length() <= 20));
    }
}
