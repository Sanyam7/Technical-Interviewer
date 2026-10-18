package com.techinterviewer.interview.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One idea a good answer to a question should contain. Stored in {@code questions.expected_key_points}; it is
 * <b>server-only</b> and must never be sent to a candidate.
 *
 * @param id             identifier unique within the question (for example {@code kp1}); evaluations refer to it
 * @param text           what the answer should say
 * @param sourceChunkIds knowledge chunks the point is grounded in (empty when the question has no grounding)
 */
public record ExpectedKeyPoint(String id, String text, List<UUID> sourceChunkIds) {

    public static final int MAX_TEXT_LENGTH = 500;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    public ExpectedKeyPoint {
        Require.notBlank(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be 1 to 40 letters, digits, '_', '.' or '-' but was '" + id + "'");
        }
        Require.notBlank(text, "text", MAX_TEXT_LENGTH);
        sourceChunkIds = Immutables.list(sourceChunkIds);
    }
}
