package com.techinterviewer.resume.domain;

import com.techinterviewer.common.validation.Require;

/**
 * A technology, skill or domain of the candidate and the résumé excerpt it rests on.
 *
 * @param name     a technology's canonical name or as the résumé writes it; a skill's or domain's label
 * @param evidence words copied from the résumé that show it
 */
public record ResumeFact(String name, String evidence) {

    public static final int MAX_NAME_LENGTH = 60;

    public ResumeFact {
        Require.notBlank(name, "name", MAX_NAME_LENGTH);
        Require.notBlank(evidence, "evidence", ResumeProfile.MAX_EXCERPT_LENGTH);
    }
}
