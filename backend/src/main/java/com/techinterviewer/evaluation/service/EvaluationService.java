package com.techinterviewer.evaluation.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.service.CodeEvidence;
import com.techinterviewer.coding.service.CodeSubmissions;
import com.techinterviewer.coding.service.CodingQuestions;
import com.techinterviewer.common.persistence.NoTransaction;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.AnswerScorer;
import com.techinterviewer.evaluation.AssessmentEvidence;
import com.techinterviewer.evaluation.RatingRules;
import com.techinterviewer.evaluation.Rubric;
import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.ScoreAggregator;
import com.techinterviewer.evaluation.ScoringPolicy;
import com.techinterviewer.evaluation.TestEvidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFindings;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.ModelProvenance;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.interview.domain.ArchitectureDiagram;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.AnswerBlockedException;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.CodeTestEvidence;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.DesignContext;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the judgement of the {@link AnswerEvaluator} into stored evaluations.
 *
 * <p>An assessment is checked against the question it was made for before anything is written: it must rate
 * exactly the criteria of the question's rubric (the evaluator kind follows from the question: the introduction, a
 * technical question or follow-up in the questioning section, a coding problem, a system design) and may cite only the
 * question's own key points. The overall score is computed here ({@link AnswerScorer}): ratings that contradict the
 * model's own verified findings are lowered ({@link RatingRules}), then weighted with the rubric the
 * {@link ScoringPolicy} chose for the question's skill ({@link ScoreAggregator}); the model never supplies a score. A
 * rejected assessment is never stored.
 *
 * <p>Transactions: {@link #record} runs in the caller's transaction (it fails loudly without one) so the turn
 * that stores an evaluation can also update the memory and the state in the same commit. {@link #evaluatePending}
 * calls the AI port and therefore refuses to run inside a transaction; it stores each result in a short transaction
 * of its own.
 */
@Service
public class EvaluationService {

    /** At most this many claims about the candidate's own experience are accepted from one answer. */
    public static final int MAX_CANDIDATE_CLAIMS = 10;
    /** At most this many factual errors are accepted from one answer. */
    public static final int MAX_FACTUAL_ERRORS = 20;

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final EvaluationRepository evaluations;
    private final CandidateAnswerRepository answers;
    private final InterviewQuestionRepository questions;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final CatalogService catalog;
    private final ScoringPolicy scoring;
    private final AnswerEvaluator evaluator;
    private final Clock clock;
    private final EvaluationProperties properties;
    private final EvaluationPause pause;
    private final EntityManager entityManager;
    private final CodeSubmissions codeSubmissions;
    private final CodingQuestions codingQuestions;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public EvaluationService(
            EvaluationRepository evaluations,
            CandidateAnswerRepository answers,
            InterviewQuestionRepository questions,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            CatalogService catalog,
            ScoringPolicy scoring,
            AnswerEvaluator evaluator,
            Clock clock,
            EvaluationProperties properties,
            EvaluationPause pause,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            CodeSubmissions codeSubmissions,
            CodingQuestions codingQuestions) {
        this.evaluations = evaluations;
        this.answers = answers;
        this.questions = questions;
        this.interviews = interviews;
        this.configurations = configurations;
        this.catalog = catalog;
        this.scoring = scoring;
        this.evaluator = evaluator;
        this.clock = clock;
        this.properties = properties;
        this.pause = pause;
        this.entityManager = entityManager;
        this.codeSubmissions = codeSubmissions;
        this.codingQuestions = codingQuestions;
        this.writes = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    /**
     * Validates {@code assessment} against {@code question}, computes the overall score, stores the evaluation and marks
     * {@code answer} evaluated. Idempotent: when the answer has an evaluation already and {@code assessment} produces
     * the very same one, the stored evaluation is returned and nothing is written.
     *
     * <p>Runs in the caller's transaction; the entities must be managed by it. Both refusals are decided before
     * anything is written, so they do not mark the caller's transaction rollback-only: a turn can catch
     * {@link InvalidAssessmentException}, leave the answer pending and still commit the rest of its work.
     *
     * @return the stored evaluation
     * @throws InvalidAssessmentException       when the assessment does not fit the question; nothing is stored and the
     *                                          answer keeps its status
     * @throws AnswerAlreadyEvaluatedException  when the answer has a different evaluation already
     * @throws AnswerUnassessableException      when the answer was given up (UNASSESSABLE) meanwhile: final, the evaluation is discarded
     * @throws IllegalArgumentException         when the answer is not the answer to the question, or was skipped
     * @throws org.springframework.transaction.IllegalTransactionStateException when called without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = {InvalidAssessmentException.class, AnswerAlreadyEvaluatedException.class, AnswerUnassessableException.class})
    public Evaluation record(CandidateAnswer answer, InterviewQuestion question, AnswerAssessment assessment) {
        return store(answer, question, assessment);
    }

    /**
     * Evaluates every answer of the interview that still waits for an evaluation (PENDING, never a skipped, evaluated or given-up
     * answer), and returns when none can make progress now. Each answer is asked up to {@code app.evaluation.max-attempts} times
     * in all (attempts of earlier turns count), with a bounded, doubling wait between its attempts; then it is UNASSESSABLE. A
     * safety block ends it at once. The attempt counter and the wait are stored with the answer, so the limit holds across
     * concurrent requests and process restarts. The AI port is called outside any transaction; each step is a short
     * transaction of its own.
     *
     * <p>The work goes in rounds, oldest answer first: every answer that is due gets an attempt, then the caller waits once, for
     * the longest backoff still running, and goes again. The waiting therefore adds up to the backoffs of one
     * answer (2 s and 4 s by default), however many answers are pending.
     *
     * <p>An answer whose next attempt is further away than {@code app.evaluation.backoff-max} (an attempt of another request, or
     * a crashed one whose lease has not expired) is left pending for a later call.
     *
     * <p>The caller has already checked that the interview belongs to the user.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when called inside a transaction
     */
    public PendingEvaluationResult evaluatePending(UUID interviewId) {
        NoTransaction.require("Evaluating pending answers");
        Require.nonNull(interviewId, "interviewId");
        int evaluated = 0;
        // Every round uses an attempt of some answer or waits for a backoff, so the rounds are bounded.
        for (int round = 0; round < properties.maxAttempts() * 2 + 2; round++) {
            List<UUID> pending = reads.execute(status -> answers.findPendingIdsByInterviewId(interviewId));
            Duration longestWait = null;
            boolean attempted = false;
            for (UUID answerId : pending) {
                switch (beginAttempt(interviewId, answerId)) {
                    case EvaluationAttempt.Settled settled -> {
                    }
                    case EvaluationAttempt.Waiting waiting -> {
                        if (waiting.remaining().compareTo(properties.backoffMax()) > 0) {
                            log.info("Answer {} is not due for an evaluation attempt for another {}; it stays pending", answerId, waiting.remaining());
                        } else if (longestWait == null || waiting.remaining().compareTo(longestWait) > 0) {
                            longestWait = waiting.remaining();
                        }
                    }
                    case EvaluationAttempt.Started started -> {
                        attempted = true;
                        if (runAttempt(interviewId, answerId, started)) {
                            evaluated++;
                        }
                    }
                }
            }
            if (longestWait != null) {
                pause.sleep(longestWait);
            } else if (!attempted) {
                break;
            }
        }
        int stillPending = reads.execute(status -> answers.findPendingIdsByInterviewId(interviewId)).size();
        int unassessable = reads.execute(status -> (int) answers.countByInterviewIdAndEvaluationStatus(interviewId, EvaluationStatus.UNASSESSABLE));
        return new PendingEvaluationResult(evaluated, stillPending, unassessable);
    }

    /** One attempt: ask the evaluator, store a valid result, otherwise record why it failed. True when the evaluation was stored. */
    private boolean runAttempt(UUID interviewId, UUID answerId, EvaluationAttempt.Started attempt) {
        AnswerAssessment assessment;
        try {
            assessment = evaluator.evaluate(attempt.request());
        } catch (AnswerBlockedException blocked) {
            log.warn("The provider refused to evaluate answer {} (attempt {}); it is unassessable", answerId, attempt.number());
            recordFailure(interviewId, answerId, attempt.number(), EvaluationFailure.SAFETY_BLOCK);
            return false;
        } catch (AiUnavailableException unavailable) {
            log.warn("Answer evaluation is unavailable (attempt {} of answer {})", attempt.number(), answerId);
            recordFailure(interviewId, answerId, attempt.number(), EvaluationFailure.PROVIDER_FAILURE);
            return false;
        } catch (IllegalArgumentException unusable) {
            log.warn("The evaluator built an unusable result for answer {} (attempt {}): {}", answerId, attempt.number(), unusable.getMessage());
            recordFailure(interviewId, answerId, attempt.number(), EvaluationFailure.INVALID_OUTPUT);
            return false;
        } catch (RuntimeException failure) {
            log.error("The answer evaluator failed unexpectedly (attempt {} of answer {})", attempt.number(), answerId, failure);
            recordFailure(interviewId, answerId, attempt.number(), EvaluationFailure.PROVIDER_FAILURE);
            return false;
        }
        try {
            return Boolean.TRUE.equals(writes.execute(status -> storeFor(interviewId, answerId, assessment)));
        } catch (InvalidAssessmentException rejected) {
            log.warn("Assessment of answer {} rejected (attempt {}): {}", answerId, attempt.number(), rejected.getMessage());
            recordFailure(interviewId, answerId, attempt.number(), EvaluationFailure.INVALID_OUTPUT);
        } catch (AnswerAlreadyEvaluatedException concurrent) {
            log.info("Answer {} was evaluated by a concurrent attempt", answerId);
        } catch (AnswerUnassessableException discarded) {
            log.info("Answer {} was given up meanwhile; its late evaluation is discarded", answerId);
        } catch (DataIntegrityViolationException raced) {
            if (!Boolean.TRUE.equals(reads.execute(status -> evaluations.existsByAnswerId(answerId)))) {
                throw raced;
            }
            log.info("Answer {} was evaluated by a concurrent attempt", answerId);
        }
        return false;
    }

    /**
     * Claims the next evaluation attempt of an answer, if one is due. In one transaction, under a row lock on the answer: a
     * skipped, evaluated or given-up answer is {@link EvaluationAttempt.Settled}; one that is backing off or has an attempt in
     * progress is {@link EvaluationAttempt.Waiting}; one whose attempts are used up (the last one crashed without reporting) is
     * given up; otherwise the attempt counter is raised, the claim is stored for {@code app.evaluation.attempt-lease}, and the
     * request for the evaluator is returned. Concurrent callers cannot both get {@code Started}.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when called inside a transaction
     */
    public EvaluationAttempt beginAttempt(UUID interviewId, UUID answerId) {
        NoTransaction.require("Starting an evaluation attempt");
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(answerId, "answerId");
        // A solution to a curated coding problem is tested first (once), so the evaluator receives the results and the scoring
        // rules can let the tests decide its correctness. It runs before the claim, outside any transaction.
        codeSubmissions.ensureTested(interviewId, answerId);
        return writes.execute(status -> begin(interviewId, answerId));
    }

    private EvaluationAttempt begin(UUID interviewId, UUID answerId) {
        CandidateAnswer answer = answers.findByIdAndInterviewIdForUpdate(answerId, interviewId)
                .orElseThrow(() -> new IllegalStateException("answer " + answerId + " is not part of interview " + interviewId));
        if (answer.isSkipped() || answer.getEvaluationStatus() != EvaluationStatus.PENDING) {
            return new EvaluationAttempt.Settled(answer.getEvaluationStatus());
        }
        Instant now = clock.instant();
        if (!answer.canStartAttempt(now)) {
            return new EvaluationAttempt.Waiting(Duration.between(now, answer.getNextEvaluationAt()));
        }
        if (answer.getEvaluationAttempts() >= properties.maxAttempts()) {
            // The last attempt was claimed and never reported (the process died; a reported failure gives up in the same
            // transaction): count it as a failed provider attempt and stop.
            answer.giveUp(EvaluationFailure.PROVIDER_FAILURE);
            log.warn("Answer {} used its {} attempts without a result; it is unassessable", answerId, properties.maxAttempts());
            return new EvaluationAttempt.Settled(EvaluationStatus.UNASSESSABLE);
        }
        int number = answer.startAttempt(now, now.plus(properties.attemptLease()));
        EvaluationRequest request = prepare(interviewId, answerId)
                .orElseThrow(() -> new IllegalStateException("answer " + answerId + " needs an evaluation but has no request"));
        return new EvaluationAttempt.Started(request, number);
    }

    /**
     * Records that attempt {@code attempt} failed, in a short transaction of its own. See {@link #failAttempt}.
     *
     * @return the status of the answer afterwards
     * @throws org.springframework.transaction.IllegalTransactionStateException when called inside a transaction
     */
    public EvaluationStatus recordFailure(UUID interviewId, UUID answerId, int attempt, EvaluationFailure failure) {
        NoTransaction.require("Recording a failed evaluation attempt");
        return writes.execute(status -> {
            CandidateAnswer answer = answers.findByIdAndInterviewIdForUpdate(answerId, interviewId)
                    .orElseThrow(() -> new IllegalStateException("answer " + answerId + " is not part of interview " + interviewId));
            return applyFailure(answer, attempt, failure);
        });
    }

    /**
     * Records that attempt {@code attempt} of {@code answer} failed, in the caller's transaction (the turn that found the
     * assessment invalid). Idempotent and safe under concurrency: the answer row is locked, and a report for an attempt that
     * is not the latest, or for an answer that is not PENDING any more, changes nothing. A safety block, or the last allowed
     * attempt, gives the answer up (UNASSESSABLE); any other failure schedules the next attempt after the backoff.
     *
     * @return the status of the answer afterwards
     * @throws org.springframework.transaction.IllegalTransactionStateException when called without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EvaluationStatus failAttempt(CandidateAnswer answer, int attempt, EvaluationFailure failure) {
        return applyFailure(answer, attempt, failure);
    }

    private EvaluationStatus applyFailure(CandidateAnswer answer, int attempt, EvaluationFailure failure) {
        Require.nonNull(failure, "failure");
        entityManager.refresh(answer, LockModeType.PESSIMISTIC_WRITE);
        if (answer.getEvaluationStatus() != EvaluationStatus.PENDING || answer.getEvaluationAttempts() != attempt
                || !answer.attemptInProgress()) {
            // Not the attempt in progress (an older one, a repeated report, or the answer is settled): nothing to change.
            return answer.getEvaluationStatus();
        }
        Instant now = clock.instant();
        if (!failure.retryable() || attempt >= properties.maxAttempts()) {
            answer.giveUp(failure);
            log.warn("Answer {} is unassessable after attempt {}: {}", answer.getId(), attempt, failure);
        } else {
            answer.failAttempt(attempt, failure, now.plus(properties.backoffAfter(attempt)));
        }
        return answer.getEvaluationStatus();
    }

    private boolean storeFor(UUID interviewId, UUID answerId, AnswerAssessment assessment) {
        CandidateAnswer answer = answers.findByIdAndInterviewIdForUpdate(answerId, interviewId)
                .orElseThrow(() -> new IllegalStateException("answer " + answerId + " vanished from interview " + interviewId));
        if (answer.getEvaluationStatus() == EvaluationStatus.COMPLETED) {
            return false;
        }
        InterviewQuestion question = questionOf(answer);
        store(answer, question, assessment);
        return true;
    }

    private Evaluation store(CandidateAnswer answer, InterviewQuestion question, AnswerAssessment assessment) {
        Require.nonNull(answer, "answer");
        Require.nonNull(question, "question");
        Require.nonNull(assessment, "assessment");
        if (!answer.getQuestionId().equals(question.getId())) {
            throw new IllegalArgumentException("answer " + answer.getId() + " is not an answer to question " + question.getId());
        }
        if (answer.isSkipped()) {
            throw new IllegalArgumentException("answer " + answer.getId() + " was skipped: there is nothing to evaluate");
        }
        entityManager.refresh(answer, LockModeType.PESSIMISTIC_WRITE);
        if (answer.getEvaluationStatus() == EvaluationStatus.UNASSESSABLE) {
            throw new AnswerUnassessableException(answer.getId());
        }
        Evaluation candidate = build(answer, question, assessment);
        Optional<Evaluation> stored = evaluations.findByAnswerId(answer.getId());
        if (stored.isPresent()) {
            if (!sameAs(stored.get(), candidate)) {
                throw new AnswerAlreadyEvaluatedException(answer.getId());
            }
            return stored.get();
        }
        Evaluation saved = evaluations.saveAndFlush(candidate);
        answer.markEvaluated();
        return saved;
    }

    private Evaluation build(CandidateAnswer answer, InterviewQuestion question, AnswerAssessment assessment) {
        Evaluator kind = RubricRegistry.evaluatorFor(question.getKind(), question.getSection());
        requireKnownKeyPoints(question, assessment);
        requireBounded(assessment);
        try {
            Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
            Map<Criterion, String> rationales = new EnumMap<>(Criterion.class);
            for (Map.Entry<Criterion, CriterionRating> entry : assessment.ratings().entrySet()) {
                ratings.put(entry.getKey(), entry.getValue().rating());
                rationales.put(entry.getKey(), entry.getValue().rationale());
            }
            Rubric rubric = scoring.rubric(kind, profileKey(kind, skillOf(question), codingLanguage(answer, kind, answer.getInterviewId())));
            RatingRules.Evidence evidence;
            if (kind == Evaluator.SYSTEM_DESIGN) {
                // A design dimension is capped by its own factual errors, and a rating above 1 must rest on a verified quote.
                evidence = AssessmentEvidence.design(question.getExpectedKeyPoints().size(), assessment);
            } else {
                TestEvidence tests = kind == Evaluator.CODING
                        ? codeSubmissions.stored(answer.getId()).map(CodeEvidence::forScoring).orElse(null) : null;
                evidence = new RatingRules.Evidence(question.getExpectedKeyPoints().size(), assessment.keyPoints(),
                        assessment.factualErrors().size(), assessment.flags(), tests, question.getKind() == QuestionKind.CODING);
            }
            Map<Criterion, List<String>> quotes = AssessmentEvidence.quotes(assessment);
            AnswerScorer.ScoredAnswer score = AnswerScorer.score(rubric, ratings, rationales, quotes, evidence, assessment.confidence());
            EvaluationFindings findings = new EvaluationFindings(
                    score.criterionScores(),
                    assessment.keyPoints(),
                    assessment.factualErrors(),
                    assessment.candidateClaims(),
                    assessment.summary(),
                    score.flags(),
                    score.confidence(),
                    kind == Evaluator.CODING ? assessment.codeAnalysis() : null);
            ModelProvenance provenance = new ModelProvenance(
                    assessment.promptId(), assessment.promptVersion(), assessment.model(), assessment.llmCallId());
            return Evaluation.create(answer.getId(), kind, BigDecimal.valueOf(score.overall()), findings, provenance, clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidAssessmentException("the assessment does not fit question " + question.getId() + ": " + invalid.getMessage(), invalid);
        }
    }

    private static void requireKnownKeyPoints(InterviewQuestion question, AnswerAssessment assessment) {
        Set<String> known = new HashSet<>();
        for (ExpectedKeyPoint keyPoint : question.getExpectedKeyPoints()) {
            known.add(keyPoint.id());
        }
        List<String> unknown = new ArrayList<>();
        for (KeyPointAssessment keyPoint : assessment.keyPoints()) {
            if (!known.contains(keyPoint.keyPointId())) {
                unknown.add(keyPoint.keyPointId());
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidAssessmentException("the assessment cites key points that question " + question.getId() + " does not have: " + unknown);
        }
    }

    private static void requireBounded(AnswerAssessment assessment) {
        if (assessment.candidateClaims().size() > MAX_CANDIDATE_CLAIMS) {
            throw new InvalidAssessmentException("the assessment lists " + assessment.candidateClaims().size()
                    + " candidate claims, at most " + MAX_CANDIDATE_CLAIMS + " are accepted");
        }
        if (assessment.factualErrors().size() > MAX_FACTUAL_ERRORS) {
            throw new InvalidAssessmentException("the assessment lists " + assessment.factualErrors().size()
                    + " factual errors, at most " + MAX_FACTUAL_ERRORS + " are accepted");
        }
    }

    /** Whether two evaluations say the same thing: evaluator, findings (the criterion scores fix the overall score), confidence and provenance. */
    private static boolean sameAs(Evaluation stored, Evaluation candidate) {
        return stored.getEvaluator() == candidate.getEvaluator()
                && stored.getCriterionScores().equals(candidate.getCriterionScores())
                && stored.getKeyPoints().equals(candidate.getKeyPoints())
                && stored.getFactualErrors().equals(candidate.getFactualErrors())
                && stored.getCandidateClaims().equals(candidate.getCandidateClaims())
                && stored.getSummary().equals(candidate.getSummary())
                && stored.getFlags().equals(candidate.getFlags())
                && Objects.equals(stored.getEvaluatorConfidence(), candidate.getEvaluatorConfidence())
                && Objects.equals(stored.getCodeAnalysis(), candidate.getCodeAnalysis())
                && stored.getPromptId().equals(candidate.getPromptId())
                && stored.getPromptVersion().equals(candidate.getPromptVersion())
                && stored.getModel().equals(candidate.getModel())
                && Objects.equals(stored.getLlmCallId(), candidate.getLlmCallId());
    }

    private InterviewQuestion questionOf(CandidateAnswer answer) {
        return questions.findByIdAndInterviewId(answer.getQuestionId(), answer.getInterviewId())
                .orElseThrow(() -> new IllegalStateException("question " + answer.getQuestionId() + " of answer " + answer.getId() + " is missing"));
    }

    /** The request for one pending answer, or empty when the answer needs nothing any more. */
    private Optional<EvaluationRequest> prepare(UUID interviewId, UUID answerId) {
        Optional<CandidateAnswer> found = answers.findByIdAndInterviewId(answerId, interviewId);
        if (found.isEmpty() || found.get().getEvaluationStatus() != EvaluationStatus.PENDING) {
            return Optional.empty();
        }
        CandidateAnswer answer = found.get();
        InterviewQuestion question = questionOf(answer);
        Interview interview = interviews.findById(interviewId)
                .orElseThrow(() -> new IllegalStateException("interview " + interviewId + " is missing"));
        Evaluator kind = RubricRegistry.evaluatorFor(question.getKind(), question.getSection());
        Long topicId = question.getTopicId();
        TopicInfo topic = topicId == null ? null : catalog.topicsById(Set.of(topicId)).get(topicId);
        String language = codingLanguage(answer, kind, interviewId);
        String profile = profileKey(kind, topic == null ? null : topic.skillSlug(), language);
        return Optional.of(new EvaluationRequest(
                interviewId,
                answer.getId(),
                kind,
                topic == null ? null : new TopicRef(topic.skillSlug(), topic.skillName(), topic.slug(), topic.name()),
                question.getDifficulty(),
                question.getPrompt(),
                question.getExpectedKeyPoints(),
                question.getDetails(),
                answer.getContent(),
                language,
                threadOf(question),
                MemorySummary.of(interview.getMemory()),
                scoring.focus(kind, profile),
                MemorySummary.mistakes(interview.getMemory()),
                codeTests(kind, question, answer),
                kind == Evaluator.SYSTEM_DESIGN ? designContext(question, answer) : null));
    }

    /**
     * The latest architecture diagram of the design thread (this answer's, or the most recent earlier one's) and the dimension the
     * question probes.
     */
    private DesignContext designContext(InterviewQuestion question, CandidateAnswer answer) {
        ArchitectureDiagram latest = answer.getArchitecture();
        UUID parentId = question.getParentQuestionId();
        while (latest == null && parentId != null) {
            InterviewQuestion parent = questions.findByIdAndInterviewId(parentId, question.getInterviewId())
                    .orElseThrow(() -> new IllegalStateException("parent question of " + question.getId() + " is missing"));
            latest = answers.findByQuestionIdAndInterviewId(parent.getId(), parent.getInterviewId())
                    .map(CandidateAnswer::getArchitecture)
                    .orElse(null);
            parentId = parent.getParentQuestionId();
        }
        return new DesignContext(latest == null ? null : latest.describe(), question.getProbeCriterion());
    }

    /**
     * What the evaluator is told about the tests of a coding answer: the results of a solution to a curated problem, or why
     * there are none. Null for a follow-up of a coding problem (it continues the discussion, it is not a new solution).
     */
    private @Nullable CodeTestEvidence codeTests(Evaluator kind, InterviewQuestion question, CandidateAnswer answer) {
        if (kind != Evaluator.CODING || question.getKind() != QuestionKind.CODING) {
            return null;
        }
        Optional<CodingProblem> problem = Optional.ofNullable(question.getCodingProblemId()).flatMap(codingQuestions::problem);
        if (problem.isEmpty()) {
            return CodeEvidence.notRun("No tests: the AI interviewer wrote this problem, so the correctness rating is your judgement and "
                    + "the software caps it at 3 as unverified.", null);
        }
        Optional<TestRunReport> report = codeSubmissions.stored(answer.getId());
        return report.map(run -> CodeEvidence.forPrompt(problem.get(), run))
                .orElseGet(() -> CodeEvidence.notRun("The tests could not be run (code execution is unavailable or not offered for this "
                        + "language), so the correctness rating is your judgement and the software caps it at 3 as unverified.", problem.get()));
    }

    /** The language of a code answer; a coding evaluation always needs one, so the interview's configured language fills a gap. */
    private @Nullable String codingLanguage(CandidateAnswer answer, Evaluator kind, UUID interviewId) {
        if (answer.getCodeLanguage() != null || kind != Evaluator.CODING) {
            return answer.getCodeLanguage();
        }
        return interviews.findById(interviewId)
                .flatMap(interview -> configurations.findById(interview.getConfigurationId()))
                .map(InterviewConfiguration::getCodingLanguage)
                .orElse(null);
    }

    /** The skill profile that weighs an answer: the same for the request the evaluator receives and the score computed from it. */
    private static @Nullable String profileKey(Evaluator kind, @Nullable String skillSlug, @Nullable String codingLanguage) {
        return ScoringPolicy.profileKey(kind, skillSlug, codingLanguage);
    }

    private @Nullable String skillOf(InterviewQuestion question) {
        Long topicId = question.getTopicId();
        if (topicId == null) {
            return null;
        }
        TopicInfo topic = catalog.topicsById(Set.of(topicId)).get(topicId);
        return topic == null ? null : topic.skillSlug();
    }

    /** Earlier turns of the follow-up thread the question belongs to, oldest first; empty for a root question. */
    private List<ThreadTurn> threadOf(InterviewQuestion question) {
        List<ThreadTurn> thread = new ArrayList<>();
        UUID parentId = question.getParentQuestionId();
        while (parentId != null) {
            InterviewQuestion parent = questions.findByIdAndInterviewId(parentId, question.getInterviewId())
                    .orElseThrow(() -> new IllegalStateException("parent question of " + question.getId() + " is missing"));
            CandidateAnswer parentAnswer = answers.findByQuestionIdAndInterviewId(parent.getId(), parent.getInterviewId())
                    .orElseThrow(() -> new IllegalStateException("question " + parent.getId() + " has follow-ups but no answer"));
            thread.add(0, new ThreadTurn(parent.getPrompt(), parentAnswer.getContent(), parentAnswer.isSkipped()));
            parentId = parent.getParentQuestionId();
        }
        return thread;
    }
}
