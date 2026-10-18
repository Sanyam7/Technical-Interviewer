package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The résumé experience a technical question is anchored on, chosen by the engine (AI_ARCHITECTURE.md section 2.13). Its words
 * are the candidate's (copied from the résumé and verified), so the generator receives them as untrusted data and must not
 * attribute anything to the candidate that they do not state.
 *
 * @param itemId       the experience id (x1, x2, ...)
 * @param kind         PROJECT, RESPONSIBILITY or ACHIEVEMENT
 * @param text         the statement as the résumé words it
 * @param context      the role or project it belongs to, as the résumé names it; null when unknown
 * @param technologies the technologies the statement names
 */
public record ResumeAnchor(String itemId, String kind, String text, @Nullable String context, List<String> technologies) {

    public ResumeAnchor {
        Require.notBlank(itemId, "itemId", 10);
        Require.notBlank(kind, "kind", 20);
        Require.notBlank(text, "text", 300);
        context = Require.nullOrNotBlank(context, "context", 120);
        technologies = Immutables.list(technologies);
    }
}
