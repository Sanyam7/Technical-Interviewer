package com.techinterviewer.coding.service;

import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ExampleText;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.StarterCode;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionOrigin;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a curated problem into the question a candidate sees: the statement, the constraints, the examples with their
 * outputs and the starter code in the interview's language. The expected key points go with it (server-only); the hidden
 * tests stay in the bank.
 */
@Component
public class CodingQuestions {

    private final ProblemBank bank;

    public CodingQuestions(ProblemBank bank) {
        this.bank = bank;
    }

    /**
     * A problem at exactly {@code difficulty} that this interview has not asked yet, as question content; empty when the bank
     * has none (the AI question generator then writes the problem).
     */
    public Optional<QuestionContent> choose(UUID interviewId, int difficulty, Set<String> askedProblemIds, String codingLanguage, String rubricId) {
        Optional<CodeLanguage> language = CodeLanguage.fromCode(codingLanguage);
        if (language.isEmpty()) {
            return Optional.empty();
        }
        return bank.choose(difficulty, askedProblemIds, interviewId.getLeastSignificantBits())
                .map(problem -> content(problem, language.get(), rubricId));
    }

    public Optional<CodingProblem> problem(String id) {
        return bank.find(id);
    }

    static QuestionContent content(CodingProblem problem, CodeLanguage language, String rubricId) {
        List<CodingExample> examples = problem.examples().stream()
                .map(example -> example(problem, example))
                .toList();
        QuestionDetails details = new QuestionDetails(problem.constraints(), examples, StarterCode.render(problem.signature(), language),
                language.code());
        return new QuestionContent(problem.statement(), details, problem.keyPoints(), rubricId, QuestionOrigin.STATIC, null, problem.id());
    }

    private static CodingExample example(CodingProblem problem, TestCase test) {
        return new CodingExample(ExampleText.input(problem.signature(), test), ExampleText.value(test.expected()), test.explanation());
    }
}
