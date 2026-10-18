package com.techinterviewer.resume;

import com.techinterviewer.interview.port.AiUnavailableException;
import java.util.UUID;

/**
 * Port: turns the text of a résumé into a draft profile (AI_ARCHITECTURE.md section 2.13). Implemented by the AI layer; without
 * one every call fails with {@link AiUnavailableException}. The draft is not trusted: {@link ResumeProfileVerifier} keeps only
 * what the résumé text supports, whatever the implementation returns.
 */
public interface ResumeExtractor {

    /**
     * @param userId the candidate (for the call record)
     * @param text   the cleaned résumé text without contact details, at most 30,000 characters
     * @throws AiUnavailableException when no model can produce a usable draft
     */
    ResumeExtraction extract(UUID userId, String text);
}
