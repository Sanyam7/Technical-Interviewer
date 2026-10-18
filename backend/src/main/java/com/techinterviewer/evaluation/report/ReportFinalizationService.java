package com.techinterviewer.evaluation.report;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.persistence.NoTransaction;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.evaluation.repository.InterviewLockRepository;
import com.techinterviewer.evaluation.repository.InterviewReportRepository;
import com.techinterviewer.evaluation.repository.ReportSkillScoreRepository;
import com.techinterviewer.evaluation.service.EvaluationProperties;
import com.techinterviewer.evaluation.service.EvaluationService;
import com.techinterviewer.evaluation.service.PendingEvaluationResult;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.engine.InterviewStateMachine;
import com.techinterviewer.interview.engine.StateTransition;
import com.techinterviewer.interview.engine.TransitionGuards;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.ReportNarrative;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The final evaluation of an interview (decision D10): re-evaluates the answers that are still pending, computes the
 * report deterministically, asks the narrator for the written part and completes the interview.
 *
 * <p>No transaction is open while a port is called. The work is a sequence of short transactions instead:
 * <ol>
 *   <li><b>claim</b>: lock the interview row, create or reuse the report row and set it GENERATING (attempts + 1);
 *   <li>{@link EvaluationService#evaluatePending} (the evaluator port, outside transactions);
 *   <li><b>load</b>: read the answers, questions, evaluations and topics;
 *   <li>when an answer still waits for an evaluation attempt (an attempt of another request is in progress, or the next one is
 *       further away than the backoff cap) the report is <b>FAILED</b> (retryable) and the interview stays in FINAL_EVALUATION; an
 *       answer that used up its attempts, or was refused, is UNASSESSABLE and does not hold the report back: it is disclosed and
 *       left out of the scores, and the report is COMPLETE, PARTIAL or INCONCLUSIVE ({@link ReportComposer}); otherwise the narrator port is asked (outside transactions) and its output is verified;
 *   <li><b>publish</b>: lock the interview row, store the report and its skill scores and apply REPORT_READY, which
 *       completes the interview, all in one transaction.
 * </ol>
 *
 * <p>Concurrency: claim, publish and failure each lock the interview row, so two simultaneous finalisations are
 * serialised there and at most one writes a result. A caller that finds the report GENERATING waits for the attempt in
 * progress and returns its result (a completed report, or the failure it recorded); it works on the report itself
 * only when that attempt has shown no sign of life for the whole wait limit (a crashed process). A late worker
 * that finds the report already READY writes nothing and returns the stored result.
 */
@Service
public class ReportFinalizationService implements ReportFinalizer {

    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);
    static final Duration DEFAULT_WAIT_LIMIT = Duration.ofSeconds(90);

    private static final Logger log = LoggerFactory.getLogger(ReportFinalizationService.class);
    private static final String UNEXPECTED_FAILURE = "The report could not be generated because of an internal error. Try again.";

    private sealed interface Claim {

        /** The outcome is known without work. */
        record Done(FinalizationResult result) implements Claim {
        }

        /** Another attempt is generating the report. */
        record InFlight() implements Claim {
        }

        /** This call generates the report. */
        record Claimed() implements Claim {
        }
    }

    /** What the answers of the interview look like when the report is made. */
    private record Snapshot(
            List<ReportEntry> entries, InterviewConfiguration configuration, int minutesUsed, ReportScope scope) {

        long awaiting() {
            return entries.stream().filter(ReportEntry::awaitsEvaluation).count();
        }
    }

    private final InterviewLockRepository locks;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final EvaluationRepository evaluations;
    private final InterviewReportRepository reports;
    private final ReportSkillScoreRepository skillScores;
    private final InterviewStateTransitionRepository transitions;
    private final CatalogService catalog;
    private final EvaluationService evaluationService;
    private final EvaluationProperties evaluationProperties;
    private final ReportNarrator narrator;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final Duration pollInterval;
    private final int maxPolls;

    @Autowired
    public ReportFinalizationService(
            InterviewLockRepository locks,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            EvaluationRepository evaluations,
            InterviewReportRepository reports,
            ReportSkillScoreRepository skillScores,
            InterviewStateTransitionRepository transitions,
            CatalogService catalog,
            EvaluationService evaluationService,
            EvaluationProperties evaluationProperties,
            ReportNarrator narrator,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this(locks, interviews, configurations, questions, answers, evaluations, reports, skillScores, transitions, catalog,
                evaluationService, evaluationProperties, narrator, clock, transactionManager, DEFAULT_POLL_INTERVAL, DEFAULT_WAIT_LIMIT);
    }

    ReportFinalizationService(
            InterviewLockRepository locks,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            EvaluationRepository evaluations,
            InterviewReportRepository reports,
            ReportSkillScoreRepository skillScores,
            InterviewStateTransitionRepository transitions,
            CatalogService catalog,
            EvaluationService evaluationService,
            EvaluationProperties evaluationProperties,
            ReportNarrator narrator,
            Clock clock,
            PlatformTransactionManager transactionManager,
            Duration pollInterval,
            Duration waitLimit) {
        this.locks = locks;
        this.interviews = interviews;
        this.configurations = configurations;
        this.questions = questions;
        this.answers = answers;
        this.evaluations = evaluations;
        this.reports = reports;
        this.skillScores = skillScores;
        this.transitions = transitions;
        this.catalog = catalog;
        this.evaluationService = evaluationService;
        this.evaluationProperties = evaluationProperties;
        this.narrator = narrator;
        this.clock = clock;
        this.writes = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.pollInterval = pollInterval;
        this.maxPolls = (int) Math.max(1, waitLimit.dividedBy(pollInterval));
    }

    /**
     * @throws ApiException           {@code 404 NOT_FOUND} when the interview is not one of {@code candidateId}
     * @throws IllegalStateException  when the interview is in a state before FINAL_EVALUATION or ABANDONED: the caller
     *                                must finalise only an interview that has ended its questions
     * @throws org.springframework.transaction.IllegalTransactionStateException when called inside a transaction
     */
    @Override
    public FinalizationResult finalizeInterview(UUID interviewId, UUID candidateId) {
        NoTransaction.require("Finalising an interview");
        Claim claim = claim(interviewId, candidateId, false, false);
        int polls = 0;
        while (claim instanceof Claim.InFlight) {
            if (polls++ < maxPolls) {
                pause();
                claim = claim(interviewId, candidateId, true, false);
            } else {
                log.warn("The report of interview {} is still generating after the wait limit; taking over", interviewId);
                claim = claim(interviewId, candidateId, true, true);
            }
        }
        return switch (claim) {
            case Claim.Done done -> done.result();
            case Claim.Claimed claimed -> generate(interviewId, candidateId);
            case Claim.InFlight inFlight -> throw new IllegalStateException("unreachable: the loop waits for the attempt");
        };
    }

    // ---- claim ------------------------------------------------------------------------------------------------

    /**
     * One look at the report row under the interview lock.
     *
     * @param followingAttempt this call has seen another attempt running: that attempt's failure is the result, not a
     *                         reason to start one more
     * @param takeOver         the attempt seen running has not finished within the wait limit: work on it too
     */
    private Claim claim(UUID interviewId, UUID candidateId, boolean followingAttempt, boolean takeOver) {
        return writes.execute(status -> {
            Interview interview = lock(interviewId, candidateId);
            if (interview.getState() == InterviewState.COMPLETED) {
                return new Claim.Done(readyResult());
            }
            requireFinalEvaluation(interview);
            InterviewReport report = reports.findByInterviewId(interviewId)
                    .orElseGet(() -> reports.save(InterviewReport.pending(interviewId, clock.instant())));
            return switch (report.getStatus()) {
                case GENERATING -> takeOver ? new Claim.Claimed() : new Claim.InFlight();
                case FAILED -> {
                    if (followingAttempt) {
                        yield new Claim.Done(failedResult());
                    }
                    report.markGenerating();
                    yield new Claim.Claimed();
                }
                case PENDING -> {
                    report.markGenerating();
                    yield new Claim.Claimed();
                }
                case READY -> throw new IllegalStateException("interview " + interviewId + " is in FINAL_EVALUATION with a READY report");
            };
        });
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the report", interrupted);
        }
    }

    // ---- generation -------------------------------------------------------------------------------------------

    private FinalizationResult generate(UUID interviewId, UUID candidateId) {
        try {
            return generateReport(interviewId, candidateId);
        } catch (RuntimeException failure) {
            log.error("Generating the report of interview {} failed unexpectedly", interviewId, failure);
            try {
                fail(interviewId, candidateId, UNEXPECTED_FAILURE);
            } catch (RuntimeException secondary) {
                failure.addSuppressed(secondary);
            }
            throw failure;
        }
    }

    private FinalizationResult generateReport(UUID interviewId, UUID candidateId) {
        PendingEvaluationResult evaluated = evaluationService.evaluatePending(interviewId);
        Snapshot snapshot = reads.execute(status -> load(interviewId));
        long awaiting = snapshot.awaiting();
        if (awaiting > 0) {
            log.warn("Report of interview {} cannot be made yet: {} answer(s) still wait for an evaluation attempt (evaluator stored {}, {} given up)",
                    interviewId, awaiting, evaluated.evaluated(), evaluated.unassessable());
            return fail(interviewId, candidateId, awaiting + (awaiting == 1 ? " answer still awaits" : " answers still await")
                    + " evaluation. Try again shortly.");
        }
        ComposedReport composed = ReportComposer.compose(snapshot.entries(), snapshot.minutesUsed(), evaluationProperties.minCoverage(),
                snapshot.scope());
        InterviewConfiguration configuration = snapshot.configuration();
        ReportNarrative narrative = composed
                .narrativeRequest(interviewId, configuration.getRole(), configuration.getExperienceLevel(), configuration.getInterviewType())
                .map(this::narrate)
                .orElse(null);
        ReportNarrativeDocument document = null;
        if (narrative != null) {
            NarrativeVerifier.Result verified = NarrativeVerifier.verify(narrative, composed.evaluationIds());
            document = verified.document();
            if (verified.dropped() > 0) {
                log.warn("Dropped {} narrative item(s) of interview {} that cite no evaluation of this interview", verified.dropped(), interviewId);
            }
        }
        return publish(interviewId, candidateId, composed, narrative, document);
    }

    /** The narrator's text, or null when it is unavailable: a report without narrative is complete. */
    private @Nullable ReportNarrative narrate(NarrativeRequest request) {
        try {
            return narrator.narrate(request);
        } catch (AiUnavailableException unavailable) {
            log.warn("Report narration is unavailable for interview {}; the report is stored without a narrative", request.interviewId());
            return null;
        }
    }

    /** Reads everything the report needs in one consistent read; the interview is the caller's, checked by the claim. */
    private Snapshot load(UUID interviewId) {
        Interview interview = interviews.findById(interviewId)
                .orElseThrow(() -> new IllegalStateException("interview " + interviewId + " is missing"));
        InterviewConfiguration configuration = configurations.findById(interview.getConfigurationId())
                .orElseThrow(() -> new IllegalStateException("configuration of interview " + interviewId + " is missing"));
        Map<UUID, InterviewQuestion> questionsById = new HashMap<>();
        questions.findByInterviewIdOrderBySequenceNoAsc(interviewId).forEach(question -> questionsById.put(question.getId(), question));
        Map<UUID, Evaluation> evaluationsByAnswer = new HashMap<>();
        evaluations.findByInterviewId(interviewId).forEach(evaluation -> evaluationsByAnswer.put(evaluation.getAnswerId(), evaluation));
        List<CandidateAnswer> answered = answers.findByInterviewIdOrderBySubmittedAtAscIdAsc(interviewId);

        Set<Long> topicIds = new HashSet<>();
        for (InterviewQuestion question : questionsById.values()) {
            if (countsAsSkillEvidence(question) && question.getTopicId() != null) {
                topicIds.add(question.getTopicId());
            }
        }
        Map<Long, TopicInfo> topics = catalog.topicsById(topicIds);

        List<ReportEntry> entries = new ArrayList<>();
        for (CandidateAnswer answer : answered) {
            InterviewQuestion question = questionsById.get(answer.getQuestionId());
            if (question == null) {
                throw new IllegalStateException("question " + answer.getQuestionId() + " of answer " + answer.getId() + " is missing");
            }
            Evaluation evaluation = answer.getEvaluationStatus() == EvaluationStatus.COMPLETED ? evaluationsByAnswer.get(answer.getId()) : null;
            EvaluationFailure unassessable = answer.getEvaluationStatus() == EvaluationStatus.UNASSESSABLE ? answer.getEvaluationFailure() : null;
            TopicInfo topic = countsAsSkillEvidence(question) && question.getTopicId() != null ? topics.get(question.getTopicId()) : null;
            entries.add(new ReportEntry(
                    question.getId(),
                    rootOf(question, questionsById).getId(),
                    question.getSequenceNo(),
                    question.getKind(),
                    question.getSection(),
                    topic,
                    question.getDifficulty(),
                    answer.isSkipped(),
                    evaluation == null ? null : evaluation.getId(),
                    evaluation == null ? null : evaluation.getOverallScore().doubleValue(),
                    evaluation == null ? "" : evaluation.getSummary(),
                    evaluation == null ? Set.of() : evaluation.getFlags(),
                    unassessable,
                    evaluation == null ? List.of() : KnowledgeGaps.of(question, evaluation),
                    evaluation == null ? List.of() : KnowledgeGaps.concepts(question, evaluation),
                    evaluation == null ? List.of() : evaluation.getFactualErrors(),
                    question.getQuestionType(),
                    question.getResumeItemId() != null,
                    evaluation == null ? null : communicationRating(evaluation)));
        }
        return new Snapshot(entries, configuration, minutesUsed(interview), scope(interview.getPlan(), configuration));
    }

    /** The scored rating of the evaluation's COMMUNICATION criterion; null when its rubric has none. */
    private static @Nullable Integer communicationRating(Evaluation evaluation) {
        return evaluation.getCriterionScores().stream()
                .filter(score -> score.criterion() == Criterion.COMMUNICATION)
                .map(CriterionScore::rating)
                .findFirst()
                .orElse(null);
    }

    /**
     * What the interview meant to assess (AI_ARCHITECTURE.md section 2.15): the areas its plan has questions for, and, when it asks
     * topic questions, its skills and the topics of its quotas, by name.
     */
    private ReportScope scope(InterviewPlan plan, InterviewConfiguration configuration) {
        Set<AssessmentArea> areas = EnumSet.of(AssessmentArea.COMMUNICATION);
        SectionPlan questioning = plan.section(Section.QUESTIONING).orElse(null);
        if (questioning != null) {
            if (!questioning.staged()) {
                areas.add(AssessmentArea.FUNDAMENTALS);
                if (plan.resume() != null) {
                    areas.add(AssessmentArea.PROJECT);
                }
            } else {
                if (questioning.slotsOf(InterviewStage.FUNDAMENTALS) > 0) {
                    areas.add(AssessmentArea.FUNDAMENTALS);
                }
                int practical = questioning.slotsOf(InterviewStage.PRACTICAL);
                if (practical > 0) {
                    areas.add(AssessmentArea.PRACTICAL);
                }
                if (practical > 1) {
                    areas.add(AssessmentArea.PROBLEM_SOLVING);
                }
                if (questioning.slotsOf(InterviewStage.PROJECT) > 0) {
                    areas.add(AssessmentArea.PROJECT);
                }
            }
        }
        if (plan.hasSection(Section.CODING) || plan.hasSection(Section.SYSTEM_DESIGN)) {
            areas.add(AssessmentArea.PROBLEM_SOLVING);
        }
        if (questioning == null) {
            return new ReportScope(areas, List.of(), List.of());
        }
        Map<Long, SkillRef> skills = catalog.skillsById(configuration.getSkillIds());
        List<AssessmentAreas.Subject> skillSubjects = skills.values().stream()
                .sorted(Comparator.comparing(SkillRef::slug))
                .map(skill -> new AssessmentAreas.Subject(skill.slug(), skill.name()))
                .toList();
        Map<String, String> topicNames = new HashMap<>();
        catalog.topicsForPlanning(configuration.getSkillIds())
                .forEach(topic -> topicNames.put(topic.skillSlug() + "/" + topic.slug(), topic.skillName() + " / " + topic.name()));
        List<AssessmentAreas.Subject> topicSubjects = questioning.topicQuotas().keySet().stream()
                .sorted()
                .map(key -> new AssessmentAreas.Subject(key, topicNames.getOrDefault(key, key)))
                .toList();
        return new ReportScope(areas, skillSubjects, topicSubjects);
    }

    /** Only the questioning section's topics are skill evidence: coding and design questions have no catalog topic. */
    private static boolean countsAsSkillEvidence(InterviewQuestion question) {
        return question.getSection() == Section.QUESTIONING;
    }

    private static InterviewQuestion rootOf(InterviewQuestion question, Map<UUID, InterviewQuestion> byId) {
        InterviewQuestion current = question;
        while (current.getParentQuestionId() != null) {
            current = byId.get(current.getParentQuestionId());
            if (current == null) {
                throw new IllegalStateException("the thread of question " + question.getId() + " has no root question");
            }
        }
        return current;
    }

    private static int minutesUsed(Interview interview) {
        Instant started = Objects.requireNonNull(interview.getStartedAt(), "an interview in the final evaluation has started");
        Instant ended = Objects.requireNonNull(interview.getEndedAt(), "an interview in the final evaluation has ended");
        long seconds = Math.max(0, Duration.between(started, ended).toSeconds());
        return (int) ((seconds + 30) / 60);
    }

    // ---- results ----------------------------------------------------------------------------------------------

    private FinalizationResult publish(
            UUID interviewId,
            UUID candidateId,
            ComposedReport composed,
            @Nullable ReportNarrative narrative,
            @Nullable ReportNarrativeDocument document) {
        return writes.execute(status -> {
            Interview interview = lock(interviewId, candidateId);
            if (interview.getState() == InterviewState.COMPLETED) {
                return readyResult();
            }
            requireFinalEvaluation(interview);
            InterviewReport report = reports.findByInterviewId(interviewId)
                    .orElseThrow(() -> new IllegalStateException("interview " + interviewId + " has no report row"));
            if (report.getStatus() != ReportStatus.GENERATING) {
                report.markGenerating();
            }
            Instant now = clock.instant();
            report.markReady(
                    composed.outcome(),
                    composed.overallScore(),
                    composed.recommendation(),
                    composed.sections(),
                    document,
                    composed.integrityNotes(),
                    composed.coverage(),
                    composed.knowledgeGaps(),
                    composed.assessmentAreas(),
                    narrative == null ? null : narrative.promptVersion(),
                    narrative == null ? null : narrative.llmCallId(),
                    now);
            composed.skills().forEach(skill -> skillScores.save(new ReportSkillScore(
                    report.getId(), skill.skillId(), skill.score(), skill.confidence(), skill.evidenceCount(), skill.averageDifficulty())));
            StateTransition ready = InterviewStateMachine.transition(InterviewState.FINAL_EVALUATION, InterviewEvent.REPORT_READY, TransitionGuards.NONE);
            transitions.save(interview.applyTransition(
                    InterviewState.FINAL_EVALUATION, ready.to(), ready.event(), Map.of("attempt", Integer.toString(report.getAttempts())), now));
            return readyResult();
        });
    }

    private FinalizationResult fail(UUID interviewId, UUID candidateId, String message) {
        return writes.execute(status -> {
            Interview interview = lock(interviewId, candidateId);
            if (interview.getState() == InterviewState.COMPLETED) {
                return readyResult();
            }
            requireFinalEvaluation(interview);
            InterviewReport report = reports.findByInterviewId(interviewId)
                    .orElseThrow(() -> new IllegalStateException("interview " + interviewId + " has no report row"));
            if (report.getStatus() != ReportStatus.FAILED) {
                report.markFailed(message);
                StateTransition failed = InterviewStateMachine.transition(InterviewState.FINAL_EVALUATION, InterviewEvent.REPORT_FAILED, TransitionGuards.NONE);
                transitions.save(interview.applyTransition(
                        InterviewState.FINAL_EVALUATION, failed.to(), failed.event(), Map.of("attempt", Integer.toString(report.getAttempts())), clock.instant()));
            }
            return failedResult();
        });
    }

    private static FinalizationResult readyResult() {
        return new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false);
    }

    private static FinalizationResult failedResult() {
        return new FinalizationResult(InterviewState.FINAL_EVALUATION, ReportStatus.FAILED, true);
    }

    private Interview lock(UUID interviewId, UUID candidateId) {
        return locks.lockByIdAndCandidateId(interviewId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Interview not found."));
    }

    private static void requireFinalEvaluation(Interview interview) {
        if (interview.getState() != InterviewState.FINAL_EVALUATION) {
            throw new IllegalStateException("interview " + interview.getId() + " is in state " + interview.getState()
                    + ": only an interview in FINAL_EVALUATION or COMPLETED can be finalised");
        }
    }
}
