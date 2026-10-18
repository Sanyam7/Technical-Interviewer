package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.provider.ContentBlockedException;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.ReportNarrative;
import com.techinterviewer.interview.port.SkillScoreSummary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The narrative of the final report: written from computed facts, citing only this interview's evaluations. */
class ReportNarratorAgentTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    private final ScriptedLlmProvider model = new ScriptedLlmProvider();
    private final AiStack stack = AiStack.around(model);

    private static NarrativeRequest request(double coverage, int unassessable) {
        return new NarrativeRequest(UUID.randomUUID(), TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 71.25,
                Recommendation.PROFICIENT, List.of(new SkillScoreSummary("java", "Java", 71.25, Confidence.MEDIUM, 2)),
                List.of(new EvaluationDigest(FIRST, Section.QUESTIONING, "java/concurrency", 3, 80, "Explained visibility well.", Set.of()),
                        new EvaluationDigest(SECOND, Section.QUESTIONING, "java/jvm", 2, 55, "Unsure about garbage collection.", Set.of())),
                coverage, unassessable);
    }

    private static final String NARRATIVE = """
            {"summary":"You showed solid concurrency knowledge.",
             "strengths":[{"text":"Clear on visibility.","evidence":["e1","e1"]}],
             "improvementAreas":[{"text":"Review garbage collection.","evidence":["e2"]}],
             "studyPlan":["Read about G1 regions."]}""";

    @Test
    void referencesAreMappedBackToTheEvaluationsTheyStandFor() {
        model.thenAnswer(NARRATIVE);

        ReportNarrative narrative = stack.reportNarrator().narrate(request(1.0, 0));

        assertThat(narrative.summary()).isEqualTo("You showed solid concurrency knowledge.");
        assertThat(narrative.strengths()).singleElement().satisfies(item -> assertThat(item.evaluationIds()).containsExactly(FIRST));
        assertThat(narrative.improvementAreas()).singleElement().satisfies(item -> assertThat(item.evaluationIds()).containsExactly(SECOND));
        assertThat(narrative.studyPlan()).containsExactly("Read about G1 regions.");
        assertThat(narrative.promptVersion()).isEqualTo("v2");
        assertThat(narrative.llmCallId()).isEqualTo(stack.recorder.idOf(0));
        assertThat(stack.recorder.records().getFirst().task()).isEqualTo("REPORT_WRITING");
    }

    @Test
    void theSchemaOnlyAllowsTheGivenReferencesAndThePromptStatesTheComputedFacts() {
        model.thenAnswer(NARRATIVE);

        stack.reportNarrator().narrate(request(0.72, 2));

        JsonNode schema = model.lastRequest().responseSchema();
        for (String list : List.of("strengths", "improvementAreas")) {
            assertThat(schema.at("/properties/" + list + "/items/properties/evidence/items/enum").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("e1", "e2");
        }
        assertThat(model.lastRequest().userPrompt())
                .contains("Recommendation band (computed): PROFICIENT")
                .contains("Overall score (computed, do not repeat it): 71.3 of 100")
                .contains("Share of the planned assessment that could be assessed: 72 %")
                .contains("Answers that could not be assessed (left out of every score): 2")
                .contains("- Java: 71.3 (confidence MEDIUM, 2 answer(s))")
                .containsPattern("\\[BEGIN UNTRUSTED evaluations [0-9a-f]{16}]\\ne1 \\| QUESTIONING \\| java/concurrency \\| difficulty 3 \\| score 80.0 \\| Explained visibility well\\.");
    }

    @Test
    void anUnknownReferenceIsSentBackForRepair() {
        model.thenAnswer(NARRATIVE.replace("[\"e2\"]", "[\"e9\"]")).thenAnswer(NARRATIVE);

        stack.reportNarrator().narrate(request(1.0, 0));

        assertThat(model.lastRequest().userPrompt()).contains("evidence: e9 is not one of the given references [e1, e2]");
    }

    @Test
    void anyFailureIsAnUnavailabilitySoTheReportIsStoredWithoutNarrative() {
        model.alwaysFail(new ContentBlockedException("SAFETY"));
        assertThatThrownBy(() -> stack.reportNarrator().narrate(request(1.0, 0)))
                .isInstanceOf(AiUnavailableException.class).hasMessage(AiUnavailableException.DEFAULT_DETAIL);

        ScriptedLlmProvider invalid = new ScriptedLlmProvider().always(request -> "{}");
        assertThatThrownBy(() -> AiStack.around(invalid).reportNarrator().narrate(request(1.0, 0))).isInstanceOf(AiUnavailableException.class);

        ScriptedLlmProvider unstorable = new ScriptedLlmProvider().thenAnswer(NARRATIVE.replace("solid concurrency", "solid \\u0000 concurrency"));
        assertThatThrownBy(() -> AiStack.around(unstorable).reportNarrator().narrate(request(1.0, 0))).isInstanceOf(AiUnavailableException.class);
    }
}
