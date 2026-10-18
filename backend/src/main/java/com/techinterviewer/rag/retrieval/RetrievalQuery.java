package com.techinterviewer.rag.retrieval;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Technology;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One search, built by code from what the interview already decided (never written by a model).
 *
 * @param semanticText  a descriptive text that is embedded for the vector half, for example the topic, the question and
 *                      its expected key points
 * @param keywordTerms  a short list of significant terms for the keyword half (topic names, identifiers such as
 *                      {@code @Transactional}); each term is matched on its own and the terms are OR-ed, so a chunk
 *                      that contains some of them still ranks. At most {@value #MAX_TERMS}
 * @param technologies  only chunks of these technologies; empty for any
 * @param docTypes      only these document types; empty for any
 * @param topicKey      {@code skill/topic}: chunks tagged with it get a bonus in the fusion, others still compete
 * @param difficulty    when set, chunks tagged with a difficulty more than one level away are left out (untagged
 *                      chunks always pass)
 * @param userId        whose private documents may be searched as well; null for public documents only
 * @param limit         how many chunks to return at most
 */
public record RetrievalQuery(
        String semanticText,
        List<String> keywordTerms,
        Set<Technology> technologies,
        Set<DocumentType> docTypes,
        @Nullable String topicKey,
        @Nullable Integer difficulty,
        @Nullable UUID userId,
        int limit) {

    public static final int MAX_TERMS = 12;
    public static final int MAX_SEMANTIC_LENGTH = 4000;

    public RetrievalQuery {
        Require.notBlank(semanticText, "semanticText");
        semanticText = semanticText.length() <= MAX_SEMANTIC_LENGTH ? semanticText : semanticText.substring(0, MAX_SEMANTIC_LENGTH);
        keywordTerms = keywordTerms.stream().map(String::strip).filter(term -> !term.isEmpty()).distinct().limit(MAX_TERMS).toList();
        technologies = Set.copyOf(technologies);
        docTypes = Set.copyOf(docTypes);
        if (difficulty != null) {
            Require.between(difficulty, 1, 5, "difficulty");
        }
        Require.between(limit, 1, 50, "limit");
    }
}
