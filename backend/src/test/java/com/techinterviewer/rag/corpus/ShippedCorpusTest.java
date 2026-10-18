package com.techinterviewer.rag.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.rag.Technology;
import com.techinterviewer.rag.chunking.Chunk;
import com.techinterviewer.rag.chunking.MarkdownChunker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The knowledge base that ships with the application: valid, complete and chunkable. */
class ShippedCorpusTest {

    private static final List<CorpusDocument> DOCUMENTS = new ArrayList<>();

    @BeforeAll
    static void parseEveryDocument() throws IOException {
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge-base/**/*.md")) {
            String url = file.getURL().toString();
            String path = url.substring(url.lastIndexOf("/knowledge-base/") + "/knowledge-base/".length());
            try (InputStream in = file.getInputStream()) {
                DOCUMENTS.add(CorpusParser.parse(path, in.readAllBytes()));
            }
        }
    }

    @Test
    void everyDocumentIsWellFormedAndLivesInTheFolderOfItsTechnology() {
        assertThat(DOCUMENTS).hasSizeGreaterThanOrEqualTo(45);
        assertThat(DOCUMENTS).allSatisfy(document ->
                assertThat(document.sourcePath()).startsWith(document.technology().slug() + "/"));
    }

    @Test
    void everyTechnologyHasMaterial() {
        Set<Technology> covered = EnumSet.noneOf(Technology.class);
        DOCUMENTS.forEach(document -> covered.add(document.technology()));
        assertThat(covered).containsExactlyInAnyOrder(Technology.values());
    }

    @Test
    void everyCatalogTopicHasADocumentAndEveryDocumentTopicIsInTheCatalog() throws IOException {
        Set<String> catalog = new TreeSet<>();
        Resource seed = new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/R__seed_catalog.sql");
        String sql = new String(seed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher topic = Pattern.compile("\\('([a-z-]+)', '([a-z0-9-]+)', '").matcher(sql);
        while (topic.find()) {
            catalog.add(topic.group(1) + "/" + topic.group(2));
        }
        Set<String> documented = new TreeSet<>();
        DOCUMENTS.stream().map(CorpusDocument::topicKey).filter(key -> key != null).forEach(documented::add);

        assertThat(catalog).hasSize(41);
        assertThat(documented).containsAll(catalog);
        assertThat(catalog).containsAll(documented);
    }

    @Test
    void everyDocumentChunksIntoSeveralSectionsOfReasonableSize() {
        MarkdownChunker chunker = new MarkdownChunker();
        int total = 0;
        for (CorpusDocument document : DOCUMENTS) {
            List<Chunk> chunks = chunker.chunk(document.title(), document.body());
            assertThat(chunks).as(document.sourcePath()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(chunks).as(document.sourcePath()).allSatisfy(chunk -> {
                assertThat(chunk.tokenCount()).isBetween(40, MarkdownChunker.DEFAULT_MAX_TOKENS + MarkdownChunker.DEFAULT_OVERLAP_TOKENS);
                assertThat(chunk.headingPath()).startsWith(document.title() + " › ");
            });
            total += chunks.size();
        }
        assertThat(total).as("chunks in the knowledge base").isGreaterThan(200);
    }

    @Test
    void sourcePathsAreUnique() {
        assertThat(DOCUMENTS.stream().map(CorpusDocument::sourcePath).distinct().count()).isEqualTo(DOCUMENTS.size());
        assertThat(DOCUMENTS.stream().map(CorpusDocument::contentSha256).distinct().count()).isEqualTo(DOCUMENTS.size());
    }
}
