package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;

/** An earlier question and answer of the same follow-up thread, given to the evaluator as context. */
public record ThreadTurn(String questionPrompt, String answerText, boolean skipped) {

    public ThreadTurn {
        Require.notBlank(questionPrompt, "questionPrompt", 4000);
        Require.text(answerText, "answerText", EvaluationRequest.MAX_ANSWER_LENGTH);
    }
}
