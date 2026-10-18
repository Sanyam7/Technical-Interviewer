package com.techinterviewer.interview.service;

import com.techinterviewer.resume.ResumeService;
import com.techinterviewer.resume.ResumeSnapshot;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.interview.api.dto.CreateInterviewRequest;
import com.techinterviewer.interview.api.dto.InterviewResponse;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creates an interview: a configuration (a saved one of the caller, or an inline one that is validated and saved for
 * the caller), the deterministic plan built from it, the interview in state INIT and the first row of its transition log,
 * all in one transaction. The daily limit is enforced inside that transaction.
 */
@Service
public class InterviewCreationService {

    static final String SOURCE_RULE = "Provide exactly one of configurationId and configuration.";
    static final String INLINE_PREFIX = "configuration.";
    static final String NO_RESUME = "This interview is personalised from your résumé, but none is uploaded. Upload one on your profile "
            + "or turn résumé personalisation off.";

    private final ConfigurationValidator validator;
    private final InterviewQuota quota;
    private final InterviewPlanFactory plans;
    private final InterviewLoader loader;
    private final InterviewConfigurationRepository configurations;
    private final InterviewRepository interviews;
    private final TransitionApplier transitions;
    private final SessionAssembler assembler;
    private final InterviewTransactions transactions;
    private final Clock clock;
    private final ResumeService resumes;

    InterviewCreationService(
            ConfigurationValidator validator,
            InterviewQuota quota,
            InterviewPlanFactory plans,
            InterviewLoader loader,
            InterviewConfigurationRepository configurations,
            InterviewRepository interviews,
            TransitionApplier transitions,
            SessionAssembler assembler,
            InterviewTransactions transactions,
            Clock clock,
            ResumeService resumes) {
        this.validator = validator;
        this.quota = quota;
        this.plans = plans;
        this.loader = loader;
        this.configurations = configurations;
        this.interviews = interviews;
        this.transitions = transitions;
        this.assembler = assembler;
        this.transactions = transactions;
        this.clock = clock;
        this.resumes = resumes;
    }

    /** The user's résumé, which a personalised interview needs. */
    private ResumeSnapshot requireResume(UUID userId, String field) {
        return resumes.current(userId).orElseThrow(() -> new FieldValidationException(field, NO_RESUME));
    }

    /**
     * @throws FieldValidationException when both or neither of the two sources are given, the inline configuration breaks a
     *                                  rule, no topic of its skills fits the difficulty, or it is personalised and the user has
     *                                  no résumé
     * @throws com.techinterviewer.common.error.ApiException {@code 404} for a configuration that is not the caller's,
     *                                  {@code 429 DAILY_INTERVIEW_LIMIT} when the day's share is used up
     */
    public InterviewResponse create(UUID userId, CreateInterviewRequest request) {
        if ((request.configurationId() == null) == (request.configuration() == null)) {
            throw new FieldValidationException(request.configuration() == null ? "configurationId" : "configuration", SOURCE_RULE);
        }
        ValidatedConfiguration inline = request.configuration() == null
                ? null
                : validator.validate(request.configuration(), INLINE_PREFIX);
        return transactions.write(() -> {
            quota.requireAvailable(userId);
            Instant now = clock.instant();
            InterviewConfiguration configuration = inline == null
                    ? loader.loadConfiguration(request.configurationId(), userId)
                    : configurations.save(inline.toEntity(userId, now));
            String field = inline == null ? "configurationId" : "configuration";
            ResumeSnapshot resume = configuration.isUseResume() ? requireResume(userId, inline == null ? field : INLINE_PREFIX + "useResume") : null;
            InterviewPlan plan = plans.plan(configuration, field, resume);
            Interview interview = interviews.saveAndFlush(
                    Interview.create(userId, configuration.getId(), plan, resume == null ? null : resume.id(), now));
            transitions.recordCreation(interview, now);
            return new InterviewResponse(
                    interview.getId(),
                    interview.getState(),
                    assembler.configuration(configuration),
                    PublicPlanMapper.toPublic(plan),
                    interview.getCreatedAt(),
                    interview.getVersion());
        });
    }
}
