package com.techinterviewer.rag.ingestion;

import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.rag.corpus.CorpusDocument;
import com.techinterviewer.rag.corpus.CorpusParser;
import com.techinterviewer.rag.corpus.MalformedDocumentException;
import com.techinterviewer.rag.embedding.EmbeddingService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;

/**
 * Loads the curated knowledge base ({@code classpath:knowledge-base/<technology>/<name>.md}) into the database at
 * startup, then embeds every chunk that has no vector of the active model yet.
 *
 * <p>Idempotent: a second run changes nothing and calls no model. A malformed document (bad front matter, an unknown
 * catalog skill or topic) is skipped with an error log and counted; the test of the shipped corpus requires zero.
 * Documents whose file disappeared are removed, unless no file was found at all (a packaging fault must not wipe the
 * knowledge base). An embedding failure stops embedding for this run and leaves the rest for the next startup:
 * meanwhile those chunks are found by keyword search only.
 */
public final class CuratedCorpusLoader {

    public static final String LOCATION = "classpath*:knowledge-base/**/*.md";
    private static final String ROOT = "/knowledge-base/";
    private static final Logger log = LoggerFactory.getLogger(CuratedCorpusLoader.class);

    /** What a run did. */
    public record Report(int created, int updated, int unchanged, int removed, List<String> malformed, int embedded, long stillMissing) {
    }

    private final IngestionService ingestion;
    private final ChunkEmbeddings embeddings;
    private final EmbeddingService embedder;
    private final CatalogService catalog;
    private final int batchSize;

    public CuratedCorpusLoader(IngestionService ingestion, ChunkEmbeddings embeddings, EmbeddingService embedder,
            CatalogService catalog, int batchSize) {
        this.ingestion = ingestion;
        this.embeddings = embeddings;
        this.embedder = embedder;
        this.catalog = catalog;
        this.batchSize = batchSize;
    }

    public Report load() {
        return load(resources(LOCATION));
    }

    Report load(List<Resource> files) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<String> malformed = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (Resource file : files) {
            String path = sourcePath(file);
            paths.add(path);
            try {
                CorpusDocument document = CorpusParser.parse(path, read(file));
                Long skillId = null;
                Long topicId = null;
                if (document.skillSlug() != null) {
                    SkillRef skill = catalog.findSkill(document.skillSlug()).orElseThrow(() ->
                            new MalformedDocumentException(path, List.of("skill \"" + document.skillSlug() + "\" is not in the catalog")));
                    skillId = skill.id();
                    if (document.topicSlug() != null) {
                        topicId = catalog.findTopic(document.skillSlug(), document.topicSlug()).orElseThrow(() -> new MalformedDocumentException(
                                path, List.of("topic \"" + document.topicKey() + "\" is not in the catalog"))).id();
                    }
                }
                switch (ingestion.upsertCurated(document, skillId, topicId)) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } catch (MalformedDocumentException | DataAccessException rejected) {
                log.error("Knowledge document skipped: {}", rejected.getMessage());
                malformed.add(path);
            }
        }
        int removed = files.isEmpty() ? 0 : ingestion.removeCuratedExcept(paths);
        int embedded = embedMissing();
        long stillMissing = embeddings.countMissing(embedder.model());
        Report report = new Report(created, updated, unchanged, removed, List.copyOf(malformed), embedded, stillMissing);
        log.info("Knowledge base: {} created, {} updated, {} unchanged, {} removed, {} malformed; {} chunks embedded with {}, {} still without a vector",
                created, updated, unchanged, removed, malformed.size(), embedded, embedder.model(), stillMissing);
        return report;
    }

    /** Embeds chunks without a vector of the active model, batch by batch, until none is left or a batch fails. */
    public int embedMissing() {
        int embedded = 0;
        while (true) {
            List<ChunkEmbeddings.Pending> pending = embeddings.missing(embedder.model(), batchSize);
            if (pending.isEmpty()) {
                return embedded;
            }
            try {
                List<float[]> vectors = embedder.embedDocuments(pending.stream().map(ChunkEmbeddings.Pending::embeddingText).toList());
                embeddings.save(embedder.model(), pending, vectors);
                embedded += pending.size();
            } catch (LlmException failure) {
                log.warn("Embedding the knowledge base stopped after {} chunks ({}); the rest is retried at the next startup "
                        + "and found by keyword search meanwhile", embedded, failure.getMessage());
                return embedded;
            }
        }
    }

    static String sourcePath(Resource file) {
        try {
            String url = file.getURL().toString();
            int root = url.lastIndexOf(ROOT);
            return root < 0 ? file.getFilename() : url.substring(root + ROOT.length());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static byte[] read(Resource file) {
        try (InputStream in = file.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    static List<Resource> resources(String location) {
        try {
            return List.of(new PathMatchingResourcePatternResolver().getResources(location));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
