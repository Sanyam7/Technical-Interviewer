package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.coding.domain.TestRunReport;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.service.CodeSubmissions;
import com.techinterviewer.common.api.PageResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.interview.api.dto.AnswerView;
import com.techinterviewer.interview.api.dto.ArchitectureView;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.api.dto.InterviewSummaryResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.api.dto.StateResponse;
import com.techinterviewer.interview.api.dto.TranscriptEntry;
import com.techinterviewer.interview.api.dto.TranscriptResponse;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Everything a candidate can read about their interviews. Every read is scoped to the authenticated user and applies the
 * lazy deadline first, so an interview whose time ran out is never shown as open.
 */
@Service
public class InterviewQueryService {

    static final String NO_CURRENT_QUESTION = "The interview has no open question.";

    private final InterviewReader reader;
    private final InterviewLoader loader;
    private final SessionAssembler assembler;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final EvaluationRepository evaluations;
    private final CatalogService catalog;
    private final CodeSubmissions codeSubmissions;
    private final ProblemBank problems;
    private final InterviewTransactions transactions;
    private final DeadlineService deadlines;

    InterviewQueryService(
            InterviewReader reader,
            InterviewLoader loader,
            SessionAssembler assembler,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            EvaluationRepository evaluations,
            CatalogService catalog,
            InterviewTransactions transactions,
            DeadlineService deadlines,
            CodeSubmissions codeSubmissions,
            ProblemBank problems) {
        this.reader = reader;
        this.loader = loader;
        this.assembler = assembler;
        this.interviews = interviews;
        this.configurations = configurations;
        this.questions = questions;
        this.answers = answers;
        this.evaluations = evaluations;
        this.catalog = catalog;
        this.codeSubmissions = codeSubmissions;
        this.problems = problems;
        this.transactions = transactions;
        this.deadlines = deadlines;
    }

    /**
     * The user's interviews, newest first, optionally only those in {@code state}. An interview on the page whose
     * deadline ran out is moved to FINAL_EVALUATION first, so the list never shows an interview as open after its time
     * is up.
     */
    public PageResponse<InterviewSummaryResponse> list(UUID userId, @Nullable InterviewState state, int page, int size) {
        Listing listing = transactions.read(() -> listing(userId, state, PageRequest.of(page, size)));
        if (listing.due().isEmpty()) {
            return listing.page();
        }
        listing.due().forEach(interviewId -> deadlines.expireIfDue(interviewId, userId));
        return transactions.read(() -> listing(userId, state, PageRequest.of(page, size))).page();
    }

    /** One page of summaries, and the interviews on it whose deadline ran out. */
    private record Listing(PageResponse<InterviewSummaryResponse> page, List<UUID> due) {
    }

    private Listing listing(UUID userId, @Nullable InterviewState state, PageRequest paging) {
        Page<Interview> found = state == null
                ? interviews.findPageByCandidateId(userId, paging)
                : interviews.findPageByCandidateIdAndState(userId, state, paging);
        Map<UUID, ConfigurationResponse> configurationsById = configurationsOf(userId, found.getContent());
        PageResponse<InterviewSummaryResponse> page = PageResponse.from(found.map(interview -> new InterviewSummaryResponse(
                interview.getId(),
                interview.getState(),
                configurationsById.get(interview.getConfigurationId()),
                interview.getCreatedAt(),
                interview.getStartedAt(),
                interview.getEndedAt(),
                interview.getEndReason())));
        return new Listing(page, found.getContent().stream().filter(deadlines::isDue).map(Interview::getId).toList());
    }

    public SessionResponse session(UUID userId, UUID interviewId) {
        return reader.read(interviewId, userId, assembler::session);
    }

    public StateResponse state(UUID userId, UUID interviewId) {
        return reader.read(interviewId, userId, assembler::state);
    }

    /** The questions asked so far, in order. */
    public List<QuestionResponse> questions(UUID userId, UUID interviewId) {
        return reader.read(interviewId, userId, interview -> assembler.questions(interview, loader.configurationOf(interview)));
    }

    /** @throws ApiException {@code 404} when no question is open (the interview is not started, over, or waiting for its next question) */
    public QuestionResponse currentQuestion(UUID userId, UUID interviewId) {
        return reader.read(interviewId, userId, interview -> assembler.currentQuestionOf(interview, loader.configurationOf(interview)))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NO_CURRENT_QUESTION));
    }

    /** @throws ApiException {@code 404} when the question is not one of this interview */
    public QuestionResponse question(UUID userId, UUID interviewId, UUID questionId) {
        return reader.read(interviewId, userId, interview -> assembler.question(
                interview, loader.configurationOf(interview), loader.loadQuestion(interview, questionId)));
    }

    /**
     * The questions with their answers. Feedback (scores, ratings, covered key points) is included only when the
     * interview is COMPLETED: before that the evaluations are not even read.
     */
    public TranscriptResponse transcript(UUID userId, UUID interviewId) {
        return reader.read(interviewId, userId, interview -> {
            InterviewConfiguration configuration = loader.configurationOf(interview);
            List<InterviewQuestion> asked = questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId());
            Map<UUID, CandidateAnswer> answersByQuestion = answers.findByInterviewIdOrderBySubmittedAtAscIdAsc(interview.getId()).stream()
                    .collect(Collectors.toMap(CandidateAnswer::getQuestionId, Function.identity()));
            Map<UUID, Evaluation> evaluationsByAnswer = interview.getState() == InterviewState.COMPLETED
                    ? evaluations.findByInterviewId(interview.getId()).stream()
                            .collect(Collectors.toMap(Evaluation::getAnswerId, Function.identity()))
                    : Map.of();
            List<QuestionResponse> views = assembler.views(interview, configuration, asked);
            Map<UUID, TestRunReport> testRuns = interview.getState() == InterviewState.COMPLETED
                    ? codeSubmissions.stored(answersByQuestion.values().stream().map(CandidateAnswer::getId).toList())
                    : Map.of();
            List<TranscriptEntry> entries = new ArrayList<>();
            for (int i = 0; i < asked.size(); i++) {
                InterviewQuestion question = asked.get(i);
                CandidateAnswer answer = answersByQuestion.get(question.getId());
                Evaluation evaluation = answer == null ? null : evaluationsByAnswer.get(answer.getId());
                entries.add(new TranscriptEntry(
                        views.get(i),
                        answer == null ? null : new AnswerView(answer.getContent(), answer.isSkipped(), answer.getSubmittedAt(),
                                answer.getArchitecture() == null ? null : ArchitectureView.of(answer.getArchitecture())),
                        evaluation == null ? null : FeedbackMapper.toFeedback(evaluation, question, testRuns.get(answer.getId()),
                                question.getCodingProblemId() == null ? null : problems.find(question.getCodingProblemId()).orElse(null))));
            }
            return new TranscriptResponse(interview.getId(), interview.getState(), entries);
        });
    }

    private Map<UUID, ConfigurationResponse> configurationsOf(UUID userId, List<Interview> found) {
        List<UUID> ids = found.stream().map(Interview::getConfigurationId).distinct().toList();
        List<InterviewConfiguration> owned = configurations.findByOwnerUserIdAndIdIn(userId, ids);
        Map<Long, SkillRef> skills = catalog.skillsById(
                owned.stream().flatMap(configuration -> configuration.getSkillIds().stream()).collect(Collectors.toSet()));
        Map<UUID, ConfigurationResponse> byId = new HashMap<>();
        owned.forEach(configuration -> byId.put(configuration.getId(), ConfigurationMapper.toResponse(configuration, skills)));
        return byId;
    }
}
