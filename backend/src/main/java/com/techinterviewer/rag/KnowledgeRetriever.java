package com.techinterviewer.rag;

import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the AI agents ask of the knowledge base: reference material for one task, ready for a prompt. It never fails
 * the task: when retrieval breaks, the material is empty and the agent works ungrounded, as it would with RAG off.
 */
public interface KnowledgeRetriever {

    /**
     * What a task wants.
     *
     * @param queries        the searches to run (several for a task about several gaps); may be empty
     * @param pinnedChunkIds chunks to include first, if they still exist (for example those a question's key points cite)
     * @param tokenBudget    the most the material may take of the prompt, in estimated tokens
     * @param maxChunks      the most chunks it may contain
     * @param interviewId    recorded with the query embedding call
     */
    record Grounding(List<RetrievalQuery> queries, List<UUID> pinnedChunkIds, int tokenBudget, int maxChunks, @Nullable UUID interviewId) {

        public Grounding {
            queries = List.copyOf(queries);
            pinnedChunkIds = List.copyOf(pinnedChunkIds);
            if (tokenBudget < 1 || maxChunks < 1) {
                throw new IllegalArgumentException("tokenBudget and maxChunks must be positive");
            }
        }
    }

    KnowledgeContext ground(Grounding grounding);

    /** RAG switched off: every task works ungrounded and records no chunk list. */
    KnowledgeRetriever NONE = grounding -> KnowledgeContext.DISABLED;
}
