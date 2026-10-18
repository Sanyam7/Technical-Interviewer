package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionGrounding;
import com.techinterviewer.interview.domain.QuestionGroundingId;
import com.techinterviewer.persistence.PersistenceTestBase;
import com.techinterviewer.rag.domain.KnowledgeChunk;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class QuestionGroundingRepositoryIT extends PersistenceTestBase {

    @Test
    void aGroundingKeepsItsCompositeKeyRankAndScore() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        KnowledgeChunk chunk = saveChunkOfPublicDocument('a');
        QuestionGrounding original = new QuestionGrounding(question.getId(), chunk.getId(), 3, 0.0325f);
        inTransaction(() -> groundings.saveAndFlush(original));

        QuestionGrounding loaded = inTransaction(() -> groundings.findById(new QuestionGroundingId(question.getId(), chunk.getId())).orElseThrow());

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getQuestionId()).isEqualTo(question.getId());
        assertThat(loaded.getChunkId()).isEqualTo(chunk.getId());
        assertThat(loaded.getRank()).isEqualTo(3);
        assertThat(loaded.getFusedScore()).isEqualTo(0.0325f);
        assertThat(jdbc.queryForObject("select rank from question_sources where question_id = ?", Integer.class, question.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select fused_score from question_sources where question_id = ?", Float.class, question.getId())).isEqualTo(0.0325f);
    }

    @Test
    void theSourcesOfAQuestionComeBestRankedFirstAndNeverIncludeAnotherQuestionsSources() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        InterviewQuestion other = saveQuestion(scenario, 2);
        KnowledgeChunk first = saveChunkOfPublicDocument('a');
        KnowledgeChunk second = saveChunkOfPublicDocument('b');
        KnowledgeChunk third = saveChunkOfPublicDocument('c');
        inTransaction(() -> {
            groundings.saveAndFlush(new QuestionGrounding(question.getId(), third.getId(), 3, 0.1f));
            groundings.saveAndFlush(new QuestionGrounding(question.getId(), first.getId(), 1, 0.9f));
            groundings.saveAndFlush(new QuestionGrounding(question.getId(), second.getId(), 2, 0.5f));
            groundings.saveAndFlush(new QuestionGrounding(other.getId(), first.getId(), 1, 0.9f));
        });

        assertThat(groundings.findByIdQuestionIdOrderByRankAsc(question.getId()))
                .extracting(QuestionGrounding::getChunkId).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(groundings.findByIdQuestionIdOrderByRankAsc(UuidV7.generate())).isEmpty();
    }

    @Test
    void aChunkGroundsAQuestionOnlyOnce() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        KnowledgeChunk chunk = saveChunkOfPublicDocument('a');
        inTransaction(() -> groundings.saveAndFlush(new QuestionGrounding(question.getId(), chunk.getId(), 1, 0.5f)));

        assertThatThrownBy(() -> inTransaction(() -> groundings.saveAndFlush(new QuestionGrounding(question.getId(), chunk.getId(), 2, 0.4f))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("question_sources", "question_id", question.getId())).isOne();
    }

    @Test
    void aGroundingNeedsARealQuestionAndChunk() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        KnowledgeChunk chunk = saveChunkOfPublicDocument('a');

        assertThatThrownBy(() -> inTransaction(() -> groundings.saveAndFlush(new QuestionGrounding(UUID.randomUUID(), chunk.getId(), 1, 0.5f))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> groundings.saveAndFlush(new QuestionGrounding(question.getId(), UUID.randomUUID(), 1, 0.5f))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theRankOfARowIsAtLeastOneInTheDatabaseToo() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        KnowledgeChunk chunk = saveChunkOfPublicDocument('a');

        assertThatThrownBy(() -> jdbc.update(
                "insert into question_sources (question_id, chunk_id, rank, fused_score) values (?, ?, 0, 0.5)", question.getId(), chunk.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theGroundingDisappearsWithItsQuestionAndWithItsChunkButTheOtherSideStays() {
        Scenario scenario = newScenario();
        InterviewQuestion byQuestion = saveQuestion(scenario, 1);
        InterviewQuestion byChunk = saveQuestion(scenario, 2);
        KnowledgeChunk chunkA = saveChunkOfPublicDocument('a');
        KnowledgeChunk chunkB = saveChunkOfPublicDocument('b');
        inTransaction(() -> {
            groundings.saveAndFlush(new QuestionGrounding(byQuestion.getId(), chunkA.getId(), 1, 0.5f));
            groundings.saveAndFlush(new QuestionGrounding(byChunk.getId(), chunkB.getId(), 1, 0.5f));
        });

        jdbc.update("delete from questions where id = ?", byQuestion.getId());
        jdbc.update("delete from knowledge_chunks where id = ?", chunkB.getId());

        assertThat(count("question_sources", "question_id", byQuestion.getId())).isZero();
        assertThat(count("knowledge_chunks", "id", chunkA.getId())).as("the chunk outlives the question").isOne();
        assertThat(count("question_sources", "question_id", byChunk.getId())).isZero();
        assertThat(count("questions", "id", byChunk.getId())).as("the question outlives the chunk").isOne();
    }
}
