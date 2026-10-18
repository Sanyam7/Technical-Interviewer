package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.interview.api.dto.ResumeReferenceView;
import com.techinterviewer.resume.ResumeService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.service.CodeSubmissions;
import com.techinterviewer.design.DesignProblemBank;
import com.techinterviewer.interview.api.dto.AnswerResponse;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.ProgressResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.api.dto.StateResponse;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds what a client sees of an interview: the session view, the lightweight state, the progress and the questions.
 * It reads inside the transaction of the caller and decides nothing; the numbers come from {@link ProgressCalculator}
 * and the question view from {@link QuestionViewMapper}. Every response of the interview endpoints, and later of the
 * answer flow, is assembled here, so they all agree.
 */
@Component
public class SessionAssembler {

    private final InterviewLoader loader;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final CatalogService catalog;
    private final Clock clock;
    private final ProblemBank problems;
    private final CodeSubmissions codeSubmissions;
    private final DesignProblemBank designProblems;

    private final ResumeService resumes;

    SessionAssembler(
            InterviewLoader loader,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            CatalogService catalog,
            Clock clock,
            ProblemBank problems,
            CodeSubmissions codeSubmissions,
            DesignProblemBank designProblems,
            ResumeService resumes) {
        this.resumes = resumes;
        this.loader = loader;
        this.questions = questions;
        this.answers = answers;
        this.catalog = catalog;
        this.clock = clock;
        this.problems = problems;
        this.codeSubmissions = codeSubmissions;
        this.designProblems = designProblems;
    }

    public SessionResponse session(Interview interview) {
        InterviewConfiguration configuration = loader.configurationOf(interview);
        Instant now = clock.instant();
        Map<Long, SkillRef> skills = catalog.skillsById(configuration.getSkillIds());
        QuestionResponse current = currentQuestionOf(interview, configuration).orElse(null);
        return new SessionResponse(
                interview.getId(),
                interview.getState(),
                now,
                interview.getStartedAt(),
                interview.getDeadlineAt(),
                interview.getEndReason(),
                progressFor(interview, configuration.getDurationMinutes(), now),
                current,
                awaitingNextQuestion(interview),
                interviewComplete(interview),
                interview.getVersion(),
                ConfigurationMapper.toResponse(configuration, skills));
    }

    public StateResponse state(Interview interview) {
        Instant now = clock.instant();
        return new StateResponse(
                interview.getState(),
                progressFor(interview, durationNeededBy(interview), now),
                interview.getDeadlineAt(),
                now,
                interview.getVersion(),
                awaitingNextQuestion(interview),
                interviewComplete(interview));
    }

    /**
     * What the client gets after an answer or a skip: the state and progress now, and the next question if one is open.
     * A current question that is already answered belongs to a turn that is still being processed (by this or another
     * request): it is not offered again, and the client is told the next question is on its way.
     */
    public AnswerResponse answer(Interview interview) {
        InterviewConfiguration configuration = loader.configurationOf(interview);
        QuestionResponse current = currentQuestionOf(interview, configuration).orElse(null);
        boolean turnInProgress = current != null && current.answered();
        return new AnswerResponse(
                true,
                interview.getState(),
                progressFor(interview, configuration.getDurationMinutes(), clock.instant()),
                turnInProgress ? null : current,
                awaitingNextQuestion(interview) || turnInProgress,
                interviewComplete(interview));
    }

    /** The configuration of the interview as the clients see it. */
    public ConfigurationResponse configuration(InterviewConfiguration configuration) {
        return ConfigurationMapper.toResponse(configuration, catalog.skillsById(configuration.getSkillIds()));
    }

    /** The questions asked so far, in order, each with its answered flag. */
    public List<QuestionResponse> questions(Interview interview, InterviewConfiguration configuration) {
        return views(interview, configuration, questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId()));
    }

    /** The candidate view of the given questions of the interview (in the order given), each with its answered flag. */
    public List<QuestionResponse> views(Interview interview, InterviewConfiguration configuration, List<InterviewQuestion> asked) {
        Set<UUID> answered = Set.copyOf(answers.findAnsweredQuestionIds(interview.getId()));
        QuestionViewMapper.Context context = context(interview, configuration, asked);
        return asked.stream().map(question -> QuestionViewMapper.toView(question, answered.contains(question.getId()), context)).toList();
    }

    /** One question of the interview, as the candidate sees it. */
    public QuestionResponse question(Interview interview, InterviewConfiguration configuration, InterviewQuestion question) {
        boolean answered = answers.existsByQuestionIdAndInterviewId(question.getId(), interview.getId());
        return QuestionViewMapper.toView(question, answered, context(interview, configuration, List.of(question)));
    }

    /** The question the candidate is to answer now, if the interview has an open one. */
    public Optional<QuestionResponse> currentQuestionOf(Interview interview, InterviewConfiguration configuration) {
        UUID currentId = interview.getCurrentQuestionId();
        if (currentId == null) {
            return Optional.empty();
        }
        return Optional.of(question(interview, configuration, loader.loadQuestion(interview, currentId)));
    }

    /** The accepting state, no open question, and a next question that is decided but not produced yet. */
    public static boolean awaitingNextQuestion(Interview interview) {
        return interview.getState().acceptsAnswers()
                && interview.getCurrentQuestionId() == null
                && interview.getMemory().awaitingNextQuestion();
    }

    /** The question phase is over: the interview is being evaluated or is completed. */
    public static boolean interviewComplete(Interview interview) {
        return interview.getState() == InterviewState.FINAL_EVALUATION || interview.getState() == InterviewState.COMPLETED;
    }

    /** Only an interview that was not started reports its configured duration as the time left; the others never read it. */
    private int durationNeededBy(Interview interview) {
        return interview.getState() == InterviewState.INIT ? loader.configurationOf(interview).getDurationMinutes() : 0;
    }

    private ProgressResponse progressFor(Interview interview, int durationMinutes, Instant now) {
        Optional<Section> section = ProgressCalculator.sectionOf(interview.getState(), interview.getResumeState(), interview.getMemory());
        int answeredRoots = section.map(value -> questions.countAnsweredRootQuestions(interview.getId(), value)).orElse(0);
        long remaining = ProgressCalculator.remainingSeconds(interview.getState(), interview.getDeadlineAt(), durationMinutes, now);
        return ProgressCalculator.progress(section, interview.getPlan(), answeredRoots, remaining);
    }

    private QuestionViewMapper.Context context(
            Interview interview, InterviewConfiguration configuration, List<InterviewQuestion> asked) {
        Map<Long, TopicInfo> topics = catalog.topicsById(QuestionViewMapper.topicIds(asked));
        String language = configuration.getCodingLanguage();
        Map<String, String> titles = new HashMap<>();
        asked.stream().map(InterviewQuestion::getCodingProblemId).filter(Objects::nonNull)
                .forEach(id -> problems.find(id).ifPresent(problem -> titles.put(id, problem.title())));
        Map<String, String> designTitles = new HashMap<>();
        asked.stream().map(InterviewQuestion::getDesignProblemId).filter(Objects::nonNull)
                .forEach(id -> designProblems.find(id).ifPresent(problem -> designTitles.put(id, problem.title())));
        return new QuestionViewMapper.Context(topics, interview.getPlan(), language, titles,
                language != null && codeSubmissions.testable(language), designTitles, resumeExperiences(interview, asked));
    }

    /** The résumé experiences the given questions are anchored on, read from the interview's résumé (none once it is gone). */
    private Map<String, ResumeReferenceView> resumeExperiences(Interview interview, List<InterviewQuestion> asked) {
        if (interview.getResumeId() == null || asked.stream().allMatch(question -> question.getResumeItemId() == null)) {
            return Map.of();
        }
        Map<String, ResumeReferenceView> views = new HashMap<>();
        resumes.profile(interview.getResumeId()).ifPresent(profile -> profile.experiences().forEach(experience ->
                views.put(experience.id(), new ResumeReferenceView(experience.kind().name(), experience.text(), experience.context()))));
        return views;
    }
}
