package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.KnowledgeRetriever.Grounding;
import com.techinterviewer.rag.Technology;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The searches each task runs: built from the interview's decisions, scoped by technology, bounded in size. */
class GroundingQueriesTest {

    private static final TopicRef TRANSACTIONS = new TopicRef("spring-boot", "Spring Boot", "transactions", "Transactions");
    private static final UUID INTERVIEW = UUID.randomUUID();

    private static QuestionRequest question(QuestionKind kind, Section section, TopicRef topic, String language, FollowUpTarget followUp) {
        return new QuestionRequest(INTERVIEW, section, kind, topic, 3, TargetRole.BACKEND, ExperienceLevel.MID, language,
                List.of("Java", "PostgreSQL"), List.of(), List.of(), followUp);
    }

    @Test
    void aSkillReachesItsOwnTechnologyAndTheNeighboursThatHelp() {
        assertThat(GroundingQueries.scopeOfSkill("react")).containsExactlyInAnyOrder(Technology.REACT, Technology.JAVASCRIPT);
        assertThat(GroundingQueries.scopeOfSkill("postgresql")).containsExactlyInAnyOrder(Technology.SQL, Technology.DATABASES);
        assertThat(GroundingQueries.scopeOfSkill("unknown")).as("an unknown skill is not restricted").isEmpty();
        assertThat(GroundingQueries.codingScope("Java"))
                .containsExactlyInAnyOrder(Technology.DATA_STRUCTURES, Technology.ALGORITHMS, Technology.JAVA);
        assertThat(GroundingQueries.codingScope("python")).containsExactlyInAnyOrder(Technology.DATA_STRUCTURES, Technology.ALGORITHMS);
    }

    @Test
    void aTechnicalQuestionSearchesItsTopicAtItsDifficulty() {
        Grounding grounding = GroundingQueries.question(question(QuestionKind.TECHNICAL, Section.QUESTIONING, TRANSACTIONS, null, null));

        RetrievalQuery query = grounding.queries().getFirst();
        assertThat(query.semanticText()).startsWith("Spring Boot: Transactions.");
        assertThat(query.keywordTerms()).as("no identifier in a topic name: the vector half searches").isEmpty();
        assertThat(query.technologies()).containsExactlyInAnyOrder(Technology.SPRING_BOOT, Technology.JAVA, Technology.REST);
        assertThat(query.topicKey()).isEqualTo("spring-boot/transactions");
        assertThat(query.difficulty()).isEqualTo(3);
        assertThat(grounding.tokenBudget()).isEqualTo(GroundingQueries.QUESTION_BUDGET);
        assertThat(grounding.interviewId()).isEqualTo(INTERVIEW);
    }

    @Test
    void codingAndDesignProblemsSearchByTheKindOfProblem() {
        RetrievalQuery coding = GroundingQueries.question(question(QuestionKind.CODING, Section.CODING, null, "java", null)).queries().getFirst();
        assertThat(coding.technologies()).contains(Technology.ALGORITHMS, Technology.JAVA);
        RetrievalQuery design = GroundingQueries.question(question(QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, null, null)).queries().getFirst();
        assertThat(design.technologies()).isEqualTo(GroundingQueries.DESIGN_SCOPE);
        assertThat(design.semanticText()).contains("Java, PostgreSQL");
    }

    @Test
    void aGapProbeSearchesTheParentQuestionAndTheMissedKeyPointButNeverExemplars() {
        FollowUpTarget target = new FollowUpTarget(FollowUpMode.PROBE_GAP, "When does @Transactional not start a transaction?",
                "When the method is private.", new ExpectedKeyPoint("kp2", "self-invocation bypasses the proxy", List.of()), null);
        Grounding grounding = GroundingQueries.followUp(question(QuestionKind.FOLLOW_UP, Section.QUESTIONING, TRANSACTIONS, null, target));

        RetrievalQuery query = grounding.queries().getFirst();
        assertThat(query.semanticText()).contains("@Transactional").contains("self-invocation bypasses the proxy");
        assertThat(query.semanticText()).as("the candidate's words never steer retrieval").doesNotContain("private");
        assertThat(query.keywordTerms()).containsExactly("@Transactional");
        assertThat(query.docTypes()).containsExactlyInAnyOrder(DocumentType.REFERENCE, DocumentType.DESIGN_REFERENCE);
        assertThat(grounding.maxChunks()).isEqualTo(GroundingQueries.FOLLOW_UP_CHUNKS);
    }

    @Test
    void anEvaluationPinsWhatTheKeyPointsCiteAndSearchesTheQuestionWithItsKeyPoints() {
        UUID cited = UUID.randomUUID();
        EvaluationRequest request = new EvaluationRequest(INTERVIEW, UUID.randomUUID(), Evaluator.TECHNICAL, TRANSACTIONS, 3,
                "Why can ConcurrentHashMap or SERIALIZABLE matter here?",
                List.of(new ExpectedKeyPoint("kp1", "propagation REQUIRES_NEW suspends the caller", List.of(cited)),
                        new ExpectedKeyPoint("kp2", "rollback on unchecked exceptions", List.of(cited))),
                null, "An answer.", null, List.of(), "");

        Grounding grounding = GroundingQueries.evaluation(request);

        assertThat(grounding.pinnedChunkIds()).containsExactly(cited, cited);
        RetrievalQuery query = grounding.queries().getFirst();
        assertThat(query.semanticText()).contains("REQUIRES_NEW").contains("rollback on unchecked").doesNotContain("An answer.");
        assertThat(query.keywordTerms()).containsExactly("ConcurrentHashMap", "SERIALIZABLE", "REQUIRES_NEW");
    }

    @Test
    void theIntroductionIsNotGrounded() {
        EvaluationRequest intro = new EvaluationRequest(INTERVIEW, UUID.randomUUID(), Evaluator.INTRO, null, 1, "Tell me about you.",
                List.of(), null, "Hi.", null, List.of(), "");
        assertThatThrownBy(() -> GroundingQueries.evaluation(intro)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theReportSearchesTheGapsOfTheWeakestAnswersFirstAtMostFour() {
        List<EvaluationDigest> digests = List.of(
                digest(80, "java/concurrency", Section.QUESTIONING, List.of("Missed: CAS loops")),
                digest(30, "postgresql/indexing", Section.QUESTIONING, List.of("Missed: leftmost prefix rule")),
                digest(55, null, Section.CODING, List.of("Partly covered: O(n log n) bound")),
                digest(10, null, Section.SYSTEM_DESIGN, List.of("Missed: cache stampede")),
                digest(95, "react/hooks", Section.QUESTIONING, List.of()),
                digest(60, "react/hooks", Section.QUESTIONING, List.of("Missed: stale closures")));
        NarrativeRequest request = new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 55,
                Recommendation.DEVELOPING, List.of(), digests);

        List<RetrievalQuery> queries = GroundingQueries.report(request).queries();

        assertThat(queries).extracting(RetrievalQuery::semanticText).containsExactly(
                "system design: Missed: cache stampede",
                "indexing: Missed: leftmost prefix rule",
                "coding: Partly covered: O(n log n) bound",
                "hooks: Missed: stale closures");
        assertThat(queries.get(1).technologies()).containsExactlyInAnyOrder(Technology.SQL, Technology.DATABASES);
        assertThat(queries.get(0).technologies()).isEqualTo(GroundingQueries.DESIGN_SCOPE);
        assertThat(queries.get(1).topicKey()).isEqualTo("postgresql/indexing");
    }

    @Test
    void keywordsAreTheExactIdentifiersOfTheTextAtMostTwelve() {
        assertThat(GroundingQueries.identifiers("Why does useEffect run twice with @Async, and is HashMap or SERIALIZABLE involved?"))
                .containsExactly("useEffect", "@Async", "HashMap", "SERIALIZABLE");
        assertThat(GroundingQueries.identifiers("Dependency injection and proxies")).isEmpty();
        assertThat(GroundingQueries.identifiers("ofA1 ofB2 ofC3 ofD4 ofE5 ofF6 ofG7 ofH8 ofI9 ofJ0 ofKa ofLb ofMc ofNd")).hasSize(12);
    }

    private static EvaluationDigest digest(double score, String topic, Section section, List<String> gaps) {
        return new EvaluationDigest(UUID.randomUUID(), section, topic, 3, score, "A summary.", Set.of(), gaps);
    }
}
