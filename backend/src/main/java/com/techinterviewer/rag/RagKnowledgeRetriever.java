package com.techinterviewer.rag;

import com.techinterviewer.rag.context.ContextAssembler;
import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.retrieval.ChunkSearch;
import com.techinterviewer.rag.retrieval.HybridRetriever;
import com.techinterviewer.rag.retrieval.RetrievedChunk;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The knowledge retriever: hybrid search, then context assembly; a failure gives empty material and a warning. */
public final class RagKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeRetriever.class);

    private final HybridRetriever retriever;
    private final ChunkSearch search;

    public RagKnowledgeRetriever(HybridRetriever retriever, ChunkSearch search) {
        this.retriever = retriever;
        this.search = search;
    }

    @Override
    public KnowledgeContext ground(Grounding grounding) {
        try {
            List<RetrievedChunk> pinned = pinned(grounding.pinnedChunkIds());
            List<List<RetrievedChunk>> ranked = retriever.retrieveAll(grounding.queries(), grounding.interviewId());
            return ContextAssembler.assemble(pinned, ranked, grounding.tokenBudget(), grounding.maxChunks());
        } catch (RuntimeException failure) {
            log.warn("Retrieval failed for interview {}; the task continues without reference material", grounding.interviewId(), failure);
            return KnowledgeContext.EMPTY;
        }
    }

    private List<RetrievedChunk> pinned(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, ChunkSearch.ChunkRow> rows = search.fetch(ids, null).stream()
                .collect(Collectors.toMap(ChunkSearch.ChunkRow::id, Function.identity()));
        return ids.stream().distinct().map(rows::get).filter(Objects::nonNull)
                .map(row -> new RetrievedChunk(row.id(), row.title(), row.sourcePath(), row.technology(), row.docType(),
                        row.headingPath(), row.content(), row.tokenCount(), row.topicKey(), null, null, null, 0))
                .toList();
    }
}
