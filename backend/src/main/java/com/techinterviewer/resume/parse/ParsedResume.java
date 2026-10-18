package com.techinterviewer.resume.parse;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.resume.ResumeFileType;
import org.jspecify.annotations.Nullable;

/**
 * The text of an uploaded résumé, cleaned ({@link ResumeText}) and within the limits.
 *
 * @param type      what the document is
 * @param pageCount pages of a PDF; null for other documents
 * @param text      the cleaned text, at most {@value ResumeLimits#MAX_TEXT_CHARS} characters
 */
public record ParsedResume(ResumeFileType type, @Nullable Integer pageCount, String text) {

    public ParsedResume {
        Require.nonNull(type, "type");
        if (pageCount != null) {
            Require.between(pageCount, 1, ResumeLimits.MAX_PAGES, "pageCount");
        }
        Require.notBlank(text, "text", ResumeLimits.MAX_TEXT_CHARS);
    }
}
