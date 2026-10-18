package com.techinterviewer.resume.domain;

import com.techinterviewer.common.validation.Require;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * A role the résumé lists.
 *
 * @param title        the job title as written
 * @param organization the employer as written; null when the résumé names none or it could not be found
 * @param start        first month (a year alone counts from its January)
 * @param end          last month; null while the role is current
 * @param evidence     the résumé line that shows the role
 */
public record ResumePosition(String title, @Nullable String organization, YearMonth start, @Nullable YearMonth end, String evidence) {

    public ResumePosition {
        Require.notBlank(title, "title", ResumeProfile.MAX_CONTEXT_LENGTH);
        organization = Require.nullOrNotBlank(organization, "organization", ResumeProfile.MAX_CONTEXT_LENGTH);
        Require.nonNull(start, "start");
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("a position cannot end (" + end + ") before it starts (" + start + ")");
        }
        Require.notBlank(evidence, "evidence", ResumeProfile.MAX_EXCERPT_LENGTH);
    }

    public boolean current() {
        return end == null;
    }
}
