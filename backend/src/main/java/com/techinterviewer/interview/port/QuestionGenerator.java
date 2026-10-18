package com.techinterviewer.interview.port;

/**
 * Writes the next question. Implemented by the AI layer (retrieval, prompt, structured output, validators,
 * exemplar fallback); the interview engine only depends on this port. Blocking, and <b>never called inside a
 * database transaction</b>.
 */
public interface QuestionGenerator {

    /**
     * @throws AiUnavailableException when no question can be produced, not even an exemplar; the caller keeps the
     *                                decision in the interview memory and the client retries later
     */
    GeneratedQuestion generate(QuestionRequest request);
}
