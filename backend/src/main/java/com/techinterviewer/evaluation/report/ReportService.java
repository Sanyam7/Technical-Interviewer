package com.techinterviewer.evaluation.report;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.persistence.NoTransaction;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.report.dto.EvidenceResponse;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.evaluation.repository.InterviewReportRepository;
import com.techinterviewer.evaluation.repository.ReportSkillScoreRepository;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a candidate sees of the report of an interview, and the way to ask again after a failure. Every lookup is scoped
 * to the authenticated user: the interview of somebody else does not exist for the caller.
 */
@Service
public class ReportService {

    private final InterviewRepository interviews;
    private final InterviewReportRepository reports;
    private final ReportSkillScoreRepository skillScores;
    private final EvaluationRepository evaluations;
    private final CandidateAnswerRepository answers;
    private final CatalogService catalog;
    private final ReportFinalizer finalizer;
    private final TransactionTemplate reads;

    public ReportService(
            InterviewRepository interviews,
            InterviewReportRepository reports,
            ReportSkillScoreRepository skillScores,
            EvaluationRepository evaluations,
            CandidateAnswerRepository answers,
            CatalogService catalog,
            ReportFinalizer finalizer,
            PlatformTransactionManager transactionManager) {
        this.interviews = interviews;
        this.reports = reports;
        this.skillScores = skillScores;
        this.evaluations = evaluations;
        this.answers = answers;
        this.catalog = catalog;
        this.finalizer = finalizer;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    /**
     * The report as it is now: a READY or FAILED report, or a PENDING or GENERATING one (also for an interview in
     * FINAL_EVALUATION whose generation has not started).
     *
     * @throws ApiException {@code 404 NOT_FOUND} for an interview that is not the caller's; {@code 409 INTERVIEW_NOT_FINISHED}
     *                      unless the interview is in FINAL_EVALUATION or COMPLETED
     */
    public ReportResponse get(UUID candidateId, UUID interviewId) {
        return reads.execute(status -> view(candidateId, interviewId));
    }

    /**
     * Makes another attempt at the report of an interview in FINAL_EVALUATION and returns the report as it is
     * afterwards. Repeating the call is safe: a second caller waits for the attempt in progress and sees its result.
     *
     * @throws ApiException {@code 404 NOT_FOUND} for an interview that is not the caller's; {@code 409
     *                      ILLEGAL_STATE_TRANSITION} (with the current state) unless the interview is in FINAL_EVALUATION
     */
    public ReportResponse retry(UUID candidateId, UUID interviewId) {
        NoTransaction.require("Retrying a report");
        InterviewState state = reads.execute(status -> find(candidateId, interviewId).getState());
        if (state != InterviewState.FINAL_EVALUATION) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                            "The report can be retried only while the interview is in FINAL_EVALUATION.")
                    .withProperty("currentState", state.name());
        }
        finalizer.finalizeInterview(interviewId, candidateId);
        return get(candidateId, interviewId);
    }

    private ReportResponse view(UUID candidateId, UUID interviewId) {
        Interview interview = find(candidateId, interviewId);
        InterviewState state = interview.getState();
        if (state != InterviewState.FINAL_EVALUATION && state != InterviewState.COMPLETED) {
            throw new ApiException(ErrorCode.INTERVIEW_NOT_FINISHED, "The interview has not finished, so there is no report yet.")
                    .withProperty("currentState", state.name());
        }
        Optional<InterviewReport> stored = reports.findByInterviewId(interviewId);
        if (stored.isEmpty()) {
            if (state == InterviewState.COMPLETED) {
                throw new IllegalStateException("interview " + interviewId + " is COMPLETED without a report");
            }
            return ReportResponseMapper.notStarted(interviewId);
        }
        InterviewReport report = stored.get();
        List<ReportSkillScore> skills = skillScores.findByIdReportIdOrderByScoreDescIdSkillIdAsc(report.getId());
        Map<Long, SkillRef> skillRefs = catalog.skillsById(skills.stream().map(ReportSkillScore::getSkillId).collect(Collectors.toSet()));
        return ReportResponseMapper.of(report, skills, skillRefs, evidence(interviewId, ReportResponseMapper.citedEvaluations(report)));
    }

    private Interview find(UUID candidateId, UUID interviewId) {
        return interviews.findByIdAndCandidateId(interviewId, candidateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Interview not found."));
    }

    /** The answer behind each cited evaluation; only answers of this interview qualify. */
    private Map<UUID, EvidenceResponse> evidence(UUID interviewId, List<UUID> evaluationIds) {
        if (evaluationIds.isEmpty()) {
            return Map.of();
        }
        List<Evaluation> cited = evaluations.findAllById(new HashSet<>(evaluationIds));
        Map<UUID, CandidateAnswer> answersById = new HashMap<>();
        answers.findAllById(cited.stream().map(Evaluation::getAnswerId).collect(Collectors.toSet()))
                .forEach(answer -> answersById.put(answer.getId(), answer));
        Map<UUID, EvidenceResponse> evidence = new HashMap<>();
        for (Evaluation evaluation : cited) {
            CandidateAnswer answer = answersById.get(evaluation.getAnswerId());
            if (answer != null && answer.getInterviewId().equals(interviewId)) {
                evidence.put(evaluation.getId(), new EvidenceResponse(answer.getQuestionId(), ReportResponseMapper.excerpt(answer.getContent())));
            }
        }
        return evidence;
    }
}
