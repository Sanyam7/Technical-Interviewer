package com.techinterviewer.interview.service;

import com.techinterviewer.coding.service.CodingQuestions;
import com.techinterviewer.design.DesignQuestions;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionGrounding;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.DesignProbe;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionEmbedding;
import com.techinterviewer.interview.port.QuestionGenerator;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.QuestionGroundingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Produces the next question of an interview that waits for one: the interview has decided what to ask
 * ({@code memory.pendingStep}) but has no current question. Three short steps, none of them holding a transaction
 * across the port call (decision D9): read what the generator needs, call the generator, store the question. A coding
 * problem comes from the curated bank instead when it has one at the decided level (no model call then).
 *
 * <p>Safe to call at any time and from several requests at once: when the interview does not wait for a question (no
 * pending step, or a question is current already) nothing happens, and two requests that both generated a question
 * store exactly one (the loser fails on the version of the interview or on the unique sequence number of the question
 * and finds the winner's question). The generator may be called twice then; its second result is dropped.
 */
@Component
class QuestionProvisioner {

    static final String UNUSABLE_DETAIL = "The AI service returned a question that cannot be used. Try again shortly.";

    private static final Logger log = LoggerFactory.getLogger(QuestionProvisioner.class);

    private final InterviewTransactions transactions;
    private final InterviewLoader loader;
    private final InterviewWriter writer;
    private final QuestionRequests requests;
    private final QuestionGenerator generator;
    private final InterviewQuestionRepository questions;
    private final QuestionGroundingRepository grounding;
    private final InterviewRepository interviews;
    private final Clock clock;
    private final CodingQuestions codingQuestions;
    private final DesignQuestions designQuestions;

    QuestionProvisioner(
            InterviewTransactions transactions,
            InterviewLoader loader,
            InterviewWriter writer,
            QuestionRequests requests,
            QuestionGenerator generator,
            InterviewQuestionRepository questions,
            QuestionGroundingRepository grounding,
            InterviewRepository interviews,
            Clock clock,
            CodingQuestions codingQuestions,
            DesignQuestions designQuestions) {
        this.transactions = transactions;
        this.loader = loader;
        this.writer = writer;
        this.requests = requests;
        this.generator = generator;
        this.questions = questions;
        this.grounding = grounding;
        this.interviews = interviews;
        this.clock = clock;
        this.codingQuestions = codingQuestions;
        this.designQuestions = designQuestions;
    }

    /**
     * @return true when this call stored the question; false when the interview did not wait for one (any more)
     * @throws AiUnavailableException when the generator cannot produce a usable question (including one for another topic
     *                                or at another difficulty than requested); nothing changed
     * @throws com.techinterviewer.common.error.ApiException {@code 409 CONCURRENT_MODIFICATION} when a concurrent change of the
     *         interview prevented the question from being stored and no question was stored by the other request either
     */
    boolean provision(UUID userId, UUID interviewId) {
        Optional<QuestionRequests.Draft> prepared = transactions.read(() -> draft(userId, interviewId));
        if (prepared.isEmpty()) {
            return false;
        }
        QuestionRequests.Draft draft = prepared.get();
        Optional<QuestionContent> curated = transactions.read(() -> curatedProblem(interviewId, draft));
        Provided provided = curated.map(content -> new Provided(content, draft.request().targetDifficulty(), List.<UUID>of(), null))
                .orElseGet(() -> generated(draft));
        try {
            return transactions.write(() -> store(userId, interviewId, draft, provided));
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException raced) {
            boolean taken = transactions.read(() -> loader.load(interviewId, userId).getCurrentQuestionId() != null);
            if (taken) {
                return false;
            }
            throw writer.conflict(interviewId, userId, raced);
        }
    }

    /** What is stored as the question: its content, its level, its grounding and its de-duplication vector. */
    private record Provided(QuestionContent content, int difficulty, List<UUID> groundingChunkIds, @Nullable QuestionEmbedding embedding) {
    }

    private static Provided provided(QuestionRequests.Draft draft, GeneratedQuestion generated) {
        QuestionContent content = new QuestionContent(generated.prompt(), generated.details(), generated.expectedKeyPoints(), draft.rubricId(),
                generated.origin(), generated.llmCallId());
        if (draft.request().resumeAnchor() != null) {
            content = content.anchoredOn(draft.request().resumeAnchor().itemId());
        }
        if (draft.questionType() != null) {
            content = content.typed(draft.questionType(), draft.templateId());
        }
        Criterion probed = draft.step().targetCriterion();
        if (probed != null) {
            // Every question of a design thread is judged against the problem's key points: the design is evaluated as a whole.
            content = content.withKeyPoints(draft.threadKeyPoints()).probing(probed);
        }
        return new Provided(content, generated.difficulty(), generated.groundingChunkIds(), generated.embedding());
    }

    /**
     * The generator's question. A probe of a design dimension never leaves the discussion waiting: when no model can word it, the
     * scenario itself is asked as static text (AI_ARCHITECTURE.md section 2.12).
     */
    private Provided generated(QuestionRequests.Draft draft) {
        DesignProbe probe = draft.request().followUp() == null ? null : draft.request().followUp().designProbe();
        if (probe == null) {
            return provided(draft, generate(draft));
        }
        try {
            return provided(draft, generate(draft));
        } catch (AiUnavailableException unavailable) {
            log.warn("The probe of {} could not be worded by the model; its scenario is asked as it is", probe.criterion());
            QuestionContent content = new QuestionContent(probe.scenario(), null, draft.threadKeyPoints(), draft.rubricId(),
                    QuestionOrigin.STATIC, null).probing(probe.criterion());
            return new Provided(content, draft.request().targetDifficulty(), List.of(), null);
        }
    }

    /**
     * A coding problem (not a follow-up) is taken from the curated bank when it has one at exactly the decided level that this
     * interview has not asked yet: its tests then decide the solution's correctness. A system design problem is taken from the design
     * bank when it has one asked at the decided level. Otherwise the generator writes the problem.
     */
    private Optional<QuestionContent> curatedProblem(UUID interviewId, QuestionRequests.Draft draft) {
        if (draft.kind() == QuestionKind.SYSTEM_DESIGN) {
            Set<String> asked = questions.findByInterviewIdOrderBySequenceNoAsc(interviewId).stream()
                    .map(InterviewQuestion::getDesignProblemId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return designQuestions.choose(interviewId, draft.request().targetDifficulty(), asked, draft.rubricId());
        }
        if (draft.kind() != QuestionKind.CODING || draft.request().codingLanguage() == null) {
            return Optional.empty();
        }
        Set<String> asked = questions.findByInterviewIdOrderBySequenceNoAsc(interviewId).stream()
                .map(InterviewQuestion::getCodingProblemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return codingQuestions.choose(interviewId, draft.request().targetDifficulty(), asked, draft.request().codingLanguage(), draft.rubricId());
    }

    private Optional<QuestionRequests.Draft> draft(UUID userId, UUID interviewId) {
        Interview interview = loader.load(interviewId, userId);
        if (!SessionAssembler.awaitingNextQuestion(interview)) {
            return Optional.empty();
        }
        return Optional.of(requests.draft(interview, loader.configurationOf(interview), interview.getMemory().pendingStep()));
    }

    private GeneratedQuestion generate(QuestionRequests.Draft draft) {
        GeneratedQuestion generated;
        try {
            generated = generator.generate(draft.request());
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            log.error("The question generator failed unexpectedly", failure);
            throw new AiUnavailableException(UNUSABLE_DETAIL, failure);
        }
        String topic = draft.step().topic();
        if (topic != null && generated.topicSlug() != null && !topic.equals(generated.topicSlug())) {
            log.warn("The question generator answered for topic {} although {} was asked for", generated.topicSlug(), topic);
            throw new AiUnavailableException(UNUSABLE_DETAIL);
        }
        // The engine chose the difficulty (adaptation, follow-up depth): a question at another level would silently
        // undo that decision and change the weight of the answer in the report (AI_ARCHITECTURE.md 10.7).
        int target = draft.request().targetDifficulty();
        if (generated.difficulty() != target) {
            log.warn("The question generator delivered difficulty {} although {} was asked for", generated.difficulty(), target);
            throw new AiUnavailableException(UNUSABLE_DETAIL);
        }
        return generated;
    }

    /**
     * The third transaction of a turn: the question with its de-duplication vector, its grounding and the interview that now
     * has a current question.
     */
    private boolean store(UUID userId, UUID interviewId, QuestionRequests.Draft draft, Provided provided) {
        Interview interview = loader.load(interviewId, userId);
        PendingStep step = interview.getMemory().pendingStep();
        if (!SessionAssembler.awaitingNextQuestion(interview) || !draft.step().equals(step)) {
            return false;
        }
        Instant now = clock.instant();
        QuestionContent content = provided.content();
        int sequenceNo = questions.findLatestSequenceNo(interviewId) + 1;
        InterviewQuestion question = draft.step().kind() == PendingStep.Kind.FOLLOW_UP
                ? InterviewQuestion.followUp(
                        interviewId, sequenceNo, step.section(), draft.topicId(), provided.difficulty(),
                        step.parentQuestionId(), draft.followUpDepth(), content, now)
                : InterviewQuestion.root(interviewId, sequenceNo, draft.kind(), draft.topicId(), provided.difficulty(), content, now);
        questions.saveAndFlush(question);
        if (provided.embedding() != null) {
            questions.storeEmbedding(question.getId(), provided.embedding().literal(), provided.embedding().model());
        }
        List<QuestionGrounding> sources = new ArrayList<>();
        for (int i = 0; i < provided.groundingChunkIds().size(); i++) {
            sources.add(new QuestionGrounding(question.getId(), provided.groundingChunkIds().get(i), i + 1, 0f));
        }
        grounding.saveAll(sources);
        interview.replaceMemory(InterviewMemories.withSectionStarted(interview.getMemory(), step.section(), now).withPendingStep(null), now);
        interview.askQuestion(question.getId(), now);
        interviews.saveAndFlush(interview);
        return true;
    }
}
