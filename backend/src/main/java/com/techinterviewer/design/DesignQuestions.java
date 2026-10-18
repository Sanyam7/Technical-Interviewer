package com.techinterviewer.design;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionDetails;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns a curated design problem into the question a candidate sees (the brief, with the facts the interviewer gives up front
 * shown next to it) and gives the scenario to probe a dimension with. The expected key points go with the question
 * (server-only).
 */
@Component
public class DesignQuestions {

    private final DesignProblemBank bank;

    public DesignQuestions(DesignProblemBank bank) {
        this.bank = bank;
    }

    /**
     * A problem asked at {@code level} that this interview has not asked yet, as question content; empty when the bank has none (the
     * AI question generator then writes the problem).
     */
    public Optional<QuestionContent> choose(UUID interviewId, int level, Set<String> askedProblemIds, String rubricId) {
        return bank.choose(level, askedProblemIds, interviewId.getLeastSignificantBits()).map(problem -> content(problem, rubricId));
    }

    public Optional<DesignProblem> problem(@Nullable String id) {
        return id == null ? Optional.empty() : bank.find(id);
    }

    /** The scenario to probe {@code criterion} with: the problem's own when it has one, otherwise the generic question. */
    public String probe(@Nullable String problemId, Criterion criterion) {
        return problem(problemId).map(problem -> problem.probe(criterion)).orElseGet(() -> DesignProbes.generic(criterion));
    }

    static QuestionContent content(DesignProblem problem, String rubricId) {
        QuestionDetails details = new QuestionDetails(problem.facts(), List.of(), null, null);
        return QuestionContent.designProblem(problem.statement(), details, problem.keyPoints(), rubricId, problem.id());
    }
}
