package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.engine.IntroductionPrompt;
import com.techinterviewer.interview.engine.StateTransition;
import com.techinterviewer.interview.engine.TransitionGuards;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Starts and abandons interviews. Every move goes through the state machine and is guarded by the version of the
 * interview, so of two simultaneous requests exactly one wins and the other is told {@code 409}.
 */
@Service
public class InterviewLifecycleService {

    /** The introduction is the first question of every interview. */
    static final int INTRODUCTION_SEQUENCE_NO = 1;

    private final InterviewWriter writer;
    private final InterviewLoader loader;
    private final TransitionApplier transitions;
    private final InterviewRepository interviews;
    private final InterviewQuestionRepository questions;
    private final CatalogService catalog;
    private final SessionAssembler assembler;
    private final Clock clock;

    InterviewLifecycleService(
            InterviewWriter writer,
            InterviewLoader loader,
            TransitionApplier transitions,
            InterviewRepository interviews,
            InterviewQuestionRepository questions,
            CatalogService catalog,
            SessionAssembler assembler,
            Clock clock) {
        this.writer = writer;
        this.loader = loader;
        this.transitions = transitions;
        this.interviews = interviews;
        this.questions = questions;
        this.catalog = catalog;
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * INIT to INTRODUCTION: starts the clock (the deadline is the start plus the configured duration), asks the
     * introduction question (static text built from the configuration, no AI involved) and opens the introduction
     * section, in one transaction.
     *
     * @throws com.techinterviewer.common.error.ApiException {@code 409 ILLEGAL_STATE_TRANSITION} unless the interview is in INIT,
     *                                                       {@code 409 CONCURRENT_MODIFICATION} when another request changed it first
     */
    public SessionResponse start(UUID userId, UUID interviewId) {
        return writer.write(interviewId, userId, interview -> {
            Instant now = clock.instant();
            InterviewConfiguration configuration = loader.configurationOf(interview);
            StateTransition edge = transitions.edge(interview, InterviewEvent.START, TransitionGuards.NONE);
            interview.start(now, configuration.getDurationMinutes());
            transitions.apply(interview, edge, now, null);
            interview.replaceMemory(InterviewMemories.withSectionStarted(interview.getMemory(), Section.INTRODUCTION, now), now);
            // The version check happens here, before any row is inserted: of two simultaneous starts the loser fails on
            // this update and never gets as far as the unique sequence number of the question.
            interviews.saveAndFlush(interview);
            InterviewQuestion introduction = questions.saveAndFlush(introduction(interview, configuration, now));
            interview.askQuestion(introduction.getId(), now);
            interviews.saveAndFlush(interview);
            return assembler.session(interview);
        });
    }

    /**
     * INIT to ABANDONED: the candidate cancels an interview before starting it.
     *
     * @throws com.techinterviewer.common.error.ApiException {@code 409 ILLEGAL_STATE_TRANSITION} unless the interview is in INIT
     */
    public SessionResponse abandon(UUID userId, UUID interviewId) {
        return writer.write(interviewId, userId, interview -> {
            transitions.apply(interview, InterviewEvent.ABANDON, TransitionGuards.NONE, clock.instant());
            interviews.saveAndFlush(interview);
            return assembler.session(interview);
        });
    }

    private InterviewQuestion introduction(Interview interview, InterviewConfiguration configuration, Instant now) {
        Map<Long, SkillRef> skills = catalog.skillsById(configuration.getSkillIds());
        List<String> skillNames = ConfigurationMapper.skillsOf(configuration, skills).stream().map(SkillRef::name).toList();
        QuestionContent content = new QuestionContent(
                IntroductionPrompt.render(configuration.getRole(), configuration.getExperienceLevel(), skillNames),
                null,
                List.of(),
                RubricRegistry.forEvaluator(Evaluator.INTRO).id(),
                QuestionOrigin.STATIC,
                null);
        return InterviewQuestion.root(
                interview.getId(), INTRODUCTION_SEQUENCE_NO, QuestionKind.INTRO, null, interview.getPlan().difficulty().start(), content, now);
    }
}
