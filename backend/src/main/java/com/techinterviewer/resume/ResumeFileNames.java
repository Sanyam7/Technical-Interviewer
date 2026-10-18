package com.techinterviewer.resume;

import com.techinterviewer.resume.domain.CandidateResume;
import java.text.Normalizer;
import org.jspecify.annotations.Nullable;

/**
 * The client's file name, made safe to store and show. It is metadata only: it never names a path, decides a type or reaches a
 * model. The last path segment is kept (a browser may send a full path), then everything but letters, digits, spaces and
 * {@code . _ - ( ) +} is replaced, leading dots are removed (no hidden files, no {@code ..}) and the length is capped.
 */
final class ResumeFileNames {

    static final String FALLBACK = "resume";

    private ResumeFileNames() {
    }

    static String sanitize(@Nullable String original) {
        if (original == null) {
            return FALLBACK;
        }
        String name = Normalizer.normalize(original, Normalizer.Form.NFKC);
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^\\p{L}\\p{N} ._()+\\-]", "_").replaceAll("\\s+", " ").replaceAll("_+", "_");
        name = name.replaceFirst("^[.\\s_]+", "").strip();
        if (name.length() > CandidateResume.MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, CandidateResume.MAX_FILE_NAME_LENGTH).strip();
        }
        return name.isEmpty() ? FALLBACK : name;
    }
}
