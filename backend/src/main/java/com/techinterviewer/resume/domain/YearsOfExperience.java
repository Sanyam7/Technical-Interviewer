package com.techinterviewer.resume.domain;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/**
 * The candidate's years of professional experience according to the résumé.
 *
 * @param years    whole years, 0 to 60
 * @param basis    where the figure comes from
 * @param evidence the excerpt that states it (STATED only)
 */
public record YearsOfExperience(int years, Basis basis, @Nullable String evidence) {

    public enum Basis {
        /** The résumé states it ("8 years of experience"). */
        STATED,
        /** Computed from the dates of the verified positions, overlaps counted once. */
        POSITIONS
    }

    public static final int MAX_YEARS = 60;

    public YearsOfExperience {
        Require.between(years, 0, MAX_YEARS, "years");
        Require.nonNull(basis, "basis");
        evidence = Require.nullOrNotBlank(evidence, "evidence", ResumeProfile.MAX_EXCERPT_LENGTH);
        if ((basis == Basis.STATED) != (evidence != null)) {
            throw new IllegalArgumentException("a stated figure has its evidence, a computed one has none");
        }
    }
}
