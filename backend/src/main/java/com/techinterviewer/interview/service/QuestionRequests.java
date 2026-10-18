package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.design.DesignProbes;
import com.techinterviewer.design.DesignQuestions;
import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.ArchitectureDiagram;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.AskedQuestion;
import com.techinterviewer.interview.port.CuratedTemplate;
import com.techinterviewer.interview.port.DesignProbe;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ResumeAnchor;
import com.techinterviewer.resume.ResumeService;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.questionbank.QuestionBank;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a decided step ({@link PendingStep}) into what the question generator is told: the request with the
 * interview's configuration, the topic, the questions asked so far and, for a follow-up, the answer to probe. Every
 * choice in it was made by deterministic code earlier; this class only collects the facts. Reads inside the caller's
 * transaction.
 */
@Component
class QuestionRequests {

    /**
     * What one pending step needs to be turned into a question and stored.
     *
     * @param threadKeyPoints for a probe of a design thread, the key points of its problem, which every question of the thread is judged
     *                        against; empty otherwise
     * @param questionType    the type the stored question carries (AI_ARCHITECTURE.md section 2.15): the step's for a main question, the
     *                        thread's for a follow-up (EXPERIENCE for a claim probe); null outside a staged questioning section
     */
    record Draft(QuestionRequest request, PendingStep step, QuestionKind kind, @Nullable Long topicId, int followUpDepth, String rubricId,
            List<ExpectedKeyPoint> threadKeyPoints, @Nullable QuestionType questionType) {

        /** The question bank template the question is written from, if any. */
        @Nullable String templateId() {
            return request.template() == null ? null : request.template().id();
        }
    }

    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final Logger log = LoggerFactory.getLogger(QuestionRequests.class);

    private final InterviewLoader loader;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final CatalogService catalog;
    private final DesignQuestions designQuestions;
    private final ResumeService resumes;
    private final QuestionBank bank;

    QuestionRequests(InterviewLoader loader, InterviewQuestionRepository questions, CandidateAnswerRepository answers, CatalogService catalog,
            DesignQuestions designQuestions, ResumeService resumes, QuestionBank bank) {
        this.resumes = resumes;
        this.bank = bank;
        this.loader = loader;
        this.questions = questions;
        this.answers = answers;
        this.catalog = catalog;
        this.designQuestions = designQuestions;
    }

    Draft draft(Interview interview, InterviewConfiguration configuration, PendingStep step) {
        QuestionKind kind = kindOf(step);
        TopicInfo topic = step.topic() == null ? null : topicOf(step.topic());
        List<InterviewQuestion> asked = questions.findByInterviewIdOrderBySequenceNoAsc(interview.getId());
        FollowUpTarget followUp = null;
        int depth = 0;
        List<ExpectedKeyPoint> threadKeyPoints = List.of();
        QuestionType storedType = step.questionType();
        if (step.kind() == PendingStep.Kind.FOLLOW_UP) {
            InterviewQuestion parent = loader.loadQuestion(interview, step.parentQuestionId());
            if (step.followUpMode() == FollowUpMode.PROBE_DIMENSION) {
                List<InterviewQuestion> thread = threadOf(interview, parent);
                followUp = designProbeOf(interview, thread, step);
                threadKeyPoints = thread.getFirst().getExpectedKeyPoints();
            } else {
                followUp = followUpOf(interview, parent, step);
            }
            depth = parent.getFollowUpDepth() + 1;
            storedType = followUpType(parent, step);
        }
        ResumeAnchor anchor = resumeAnchor(interview, step);
        QuestionType type = step.questionType();
        if (type == QuestionType.EXPERIENCE && anchor == null && interview.getMemory().candidateClaims().isEmpty()) {
            // Anchored on a résumé that is gone since, and the candidate said nothing about their own work: nothing to discuss.
            type = QuestionType.SCENARIO;
            storedType = type;
        }
        QuestionRequest request = new QuestionRequest(
                interview.getId(),
                step.section(),
                kind,
                topic == null ? null : new TopicRef(topic.skillSlug(), topic.skillName(), topic.slug(), topic.name()),
                step.difficulty(),
                configuration.getRole(),
                configuration.getExperienceLevel(),
                step.section() == Section.CODING ? configuration.getCodingLanguage() : null,
                focusSkills(configuration),
                history(asked),
                claimsFor(interview, type, anchor),
                followUp,
                anchor,
                type,
                template(interview, step, type, asked));
        String rubricId = RubricRegistry.forEvaluator(RubricRegistry.evaluatorFor(kind, step.section())).id();
        return new Draft(request, step, kind, topic == null ? null : topic.id(), depth, rubricId, threadKeyPoints, storedType);
    }

    /**
     * What the candidate said about their own work, as far as the question may use it (AI_ARCHITECTURE.md section 2.15): an EXPERIENCE
     * question without a résumé anchor is about it; a question without a type (a plan without stages) may be tailored to it as before;
     * a fundamentals or practical question does not see it, so it stays about the technology.
     */
    private static List<String> claimsFor(Interview interview, @Nullable QuestionType type, @Nullable ResumeAnchor anchor) {
        boolean uses = type == null || (type == QuestionType.EXPERIENCE && anchor == null);
        return uses ? interview.getMemory().candidateClaims().stream().map(Claim::text).toList() : List.of();
    }

    /**
     * The curated angle for a typed main question: an unused template of the question bank for its topic, type and level, chosen by
     * the interview (AI_ARCHITECTURE.md section 2.15). None for an EXPERIENCE question, which is about the candidate's own work.
     */
    private @Nullable CuratedTemplate template(Interview interview, PendingStep step, @Nullable QuestionType type, List<InterviewQuestion> asked) {
        if (type == null || type == QuestionType.EXPERIENCE || step.topic() == null) {
            return null;
        }
        Set<String> used = asked.stream().map(InterviewQuestion::getTemplateId).filter(Objects::nonNull).collect(Collectors.toSet());
        long seed = Objects.hash(interview.getId(), step.topic());
        return bank.choose(step.topic(), type, step.difficulty(), used, seed)
                .map(selection -> new CuratedTemplate(selection.template().id(), selection.template().type(), selection.template().subtopic(),
                        selection.template().question(), selection.template().keyPoints(), selection.topic().misconceptions()))
                .orElse(null);
    }

    /**
     * The type a follow-up carries: its thread's, except that a claim probe is about the candidate's experience. Null when the thread
     * has no type (outside the questioning section, or a plan without stages).
     */
    private static @Nullable QuestionType followUpType(InterviewQuestion parent, PendingStep step) {
        if (parent.getQuestionType() == null) {
            return null;
        }
        return step.followUpMode() == FollowUpMode.PROBE_CLAIM ? QuestionType.EXPERIENCE : parent.getQuestionType();
    }

    /**
     * The résumé experience the engine anchored this step on, read from the résumé the interview is linked to. Empty when the step has
     * no anchor, or when that résumé was deleted or replaced since: the question is then an ordinary topic question.
     */
    private @Nullable ResumeAnchor resumeAnchor(Interview interview, PendingStep step) {
        if (step.resumeItemId() == null) {
            return null;
        }
        if (interview.getResumeId() == null) {
            log.info("Interview {}: the résumé it was personalised from is gone; {} is asked without its anchor", interview.getId(), step.topic());
            return null;
        }
        return resumes.profile(interview.getResumeId())
                .flatMap(profile -> profile.experience(step.resumeItemId()))
                .map(experience -> new ResumeAnchor(experience.id(), experience.kind().name(), experience.text(), experience.context(),
                        experience.technologies()))
                .orElse(null);
    }

    /** The questions of the thread that ends with {@code last}, the root problem first. */
    private List<InterviewQuestion> threadOf(Interview interview, InterviewQuestion last) {
        List<InterviewQuestion> thread = new ArrayList<>();
        for (InterviewQuestion question = last; question != null;
                question = question.getParentQuestionId() == null ? null : loader.loadQuestion(interview, question.getParentQuestionId())) {
            thread.addFirst(question);
        }
        return thread;
    }

    /**
     * The probe of a design dimension: the dimension the engine chose, the problem's scenario for it, and the design discussed so far
     * with its latest diagram.
     */
    private FollowUpTarget designProbeOf(Interview interview, List<InterviewQuestion> thread, PendingStep step) {
        List<ThreadTurn> discussion = new ArrayList<>();
        ArchitectureDiagram latest = null;
        CandidateAnswer lastAnswer = null;
        for (InterviewQuestion question : thread) {
            CandidateAnswer answer = answers.findByQuestionIdAndInterviewId(question.getId(), interview.getId())
                    .orElseThrow(() -> new IllegalStateException("question " + question.getId() + " is followed up but has no answer"));
            discussion.add(new ThreadTurn(question.getPrompt(), answer.getContent(), answer.isSkipped()));
            if (answer.getArchitecture() != null) {
                latest = answer.getArchitecture();
            }
            lastAnswer = answer;
        }
        InterviewQuestion root = thread.getFirst();
        Criterion criterion = step.targetCriterion();
        DesignProbe probe = new DesignProbe(criterion, DesignProbes.meaning(criterion), designQuestions.probe(root.getDesignProblemId(), criterion),
                root.getPrompt(), discussion, latest == null ? null : latest.describe());
        return FollowUpTarget.designProbe(thread.getLast().getPrompt(), lastAnswer.getContent(), probe);
    }

    /** The kind of the question a step asks: a follow-up, or the root question of its section. */
    static QuestionKind kindOf(PendingStep step) {
        if (step.kind() == PendingStep.Kind.FOLLOW_UP) {
            return QuestionKind.FOLLOW_UP;
        }
        return switch (step.section()) {
            case QUESTIONING -> QuestionKind.TECHNICAL;
            case CODING -> QuestionKind.CODING;
            case SYSTEM_DESIGN -> QuestionKind.SYSTEM_DESIGN;
            case INTRODUCTION -> throw new IllegalStateException("the introduction is static and never pending");
        };
    }

    private TopicInfo topicOf(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            throw new IllegalStateException("'" + key + "' is not a skill/topic key");
        }
        return catalog.findTopic(key.substring(0, slash), key.substring(slash + 1))
                .orElseThrow(() -> new IllegalStateException("topic " + key + " is no longer in the catalog"));
    }

    private FollowUpTarget followUpOf(Interview interview, InterviewQuestion parent, PendingStep step) {
        CandidateAnswer parentAnswer = answers.findByQuestionIdAndInterviewId(parent.getId(), interview.getId())
                .orElseThrow(() -> new IllegalStateException("question " + parent.getId() + " is followed up but has no answer"));
        FollowUpMode mode = step.followUpMode();
        ExpectedKeyPoint target = step.targetKeyPointId() == null ? null : parent.getExpectedKeyPoints().stream()
                .filter(keyPoint -> keyPoint.id().equals(step.targetKeyPointId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "question " + parent.getId() + " has no key point " + step.targetKeyPointId()));
        return new FollowUpTarget(mode, parent.getPrompt(), parentAnswer.getContent(), target, step.targetClaim());
    }

    private List<String> focusSkills(InterviewConfiguration configuration) {
        Map<Long, SkillRef> skills = catalog.skillsById(configuration.getSkillIds());
        return ConfigurationMapper.skillsOf(configuration, skills).stream().map(SkillRef::name).toList();
    }

    private List<AskedQuestion> history(List<InterviewQuestion> asked) {
        List<InterviewQuestion> recent = asked.size() > QuestionRequest.MAX_ASKED_QUESTIONS
                ? asked.subList(asked.size() - QuestionRequest.MAX_ASKED_QUESTIONS, asked.size())
                : asked;
        Map<Long, TopicInfo> topics = catalog.topicsById(QuestionViewMapper.topicIds(recent));
        return recent.stream()
                .map(question -> new AskedQuestion(
                        question.getTopicId() == null ? null : topics.get(question.getTopicId()).key(),
                        question.getPrompt().length() <= MAX_PROMPT_LENGTH ? question.getPrompt() : question.getPrompt().substring(0, MAX_PROMPT_LENGTH)))
                .toList();
    }
}
