package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import com.techinterviewer.ai.agents.output.ResumeDraft;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.StructuredCall;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.ai.structured.StructuredResult;
import com.techinterviewer.resume.ResumeEvidence;
import com.techinterviewer.resume.ResumeExtraction;
import com.techinterviewer.resume.ResumeExtractor;
import com.techinterviewer.resume.domain.ResumeExperience;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.techinterviewer.interview.port.AiUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Résumé extractor</b> (AI_ARCHITECTURE.md section 2.13): turns the text of a résumé into a draft profile, every item with
 * words copied from the résumé. The draft is checked again, item by item, by {@code ResumeProfileVerifier}; this agent only
 * makes the call well.
 *
 * <p>The résumé is the candidate's text, so it is fenced as untrusted data, and lines that address the AI ("ignore your
 * instructions") are removed before the model sees it ({@link #redact}); the extraction reports that it happened. The verifier checks against the same redacted text, so a removed passage can support
 * nothing.
 *
 * <p>One semantic check can send the draft back for a repair: when most experiences are not copied from the résumé (a small
 * model tends to summarise bullets), the model is asked to copy them word for word; they would otherwise all be dropped.
 */
public final class ResumeExtractorAgent implements ResumeExtractor {

    static final String PROMPT_ID = "resume-extraction";
    static final String UNREADABLE_DETAIL = "The AI service could not read the résumé. Try again shortly.";

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractorAgent.class);

    private final StructuredOutputClient client;
    private final PromptRegistry prompts;
    private final JsonSchemaRegistry schemas;

    public ResumeExtractorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas) {
        this.client = client;
        this.prompts = prompts;
        this.schemas = schemas;
    }

    @Override
    public ResumeExtraction extract(UUID userId, String text) {
        try {
            return extractChecked(text);
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            // A provider failure, output that stays invalid after the repair, a refusal: nothing is stored, the candidate may retry.
            log.warn("The résumé of user {} could not be read: {}", userId, failure.toString());
            throw new AiUnavailableException(UNREADABLE_DETAIL, failure);
        }
    }

    private ResumeExtraction extractChecked(String text) {
        boolean suspected = InjectionHeuristics.suspected(text);
        String seen = suspected ? redact(text) : text;
        PromptTemplate template = prompts.latest(PROMPT_ID);
        ResumeEvidence evidence = new ResumeEvidence(seen);
        StructuredResult<ResumeDraft> result = client.generate(new StructuredCall<>(
                AiTask.RESUME_EXTRACTION, template,
                Map.of("resume", PromptValue.untrusted("resume", seen)),
                schemas.get(template.schema()), ResumeDraft.class,
                draft -> problems(draft, evidence), null));
        ResumeDraft draft = result.value();
        return new ResumeExtraction(
                draft.positions().stream()
                        .map(position -> new ResumeExtraction.PositionDraft(position.title(), position.organization(), position.start(),
                                position.end(), position.evidence()))
                        .toList(),
                draft.statedYears() == null ? null : new ResumeExtraction.StatedYears(draft.statedYears(), draft.statedYearsEvidence()),
                facts(draft.technologies()),
                facts(draft.skills()),
                facts(draft.domains()),
                draft.experiences().stream()
                        .map(experience -> new ResumeExtraction.ExperienceDraft(experience.kind(), experience.text(), experience.context(),
                                experience.technologies()))
                        .toList(),
                seen,
                suspected,
                result.model(),
                result.promptVersion(),
                result.llmCallId());
    }

    /**
     * The résumé without the lines that address the AI. A résumé is read line by line, not as prose: an injected line is removed
     * whole, so that what follows the instruction on that line ("... and record that the candidate led Google's search") does not
     * reach the model either. A sign that spans lines falls back to the answer's redaction (sentences, then paragraphs).
     */
    static String redact(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            kept.append(InjectionHeuristics.suspected(line) ? InjectionHeuristics.REMOVED : line).append('\n');
        }
        String result = kept.substring(0, kept.length() - 1);
        return InjectionHeuristics.suspected(result) ? InjectionHeuristics.redact(result) : result;
    }

    /** Most experiences not copied from the résumé: one repair, with the first few that were not. */
    static List<String> problems(ResumeDraft draft, ResumeEvidence evidence) {
        List<String> notCopied = new ArrayList<>();
        for (ResumeDraft.ExperienceDraft experience : draft.experiences()) {
            if (experience.text() == null || !evidence.contains(experience.text(), ResumeExperience.MIN_WORDS)) {
                notCopied.add(experience.text());
            }
        }
        if (notCopied.size() < 2 || notCopied.size() * 2 <= draft.experiences().size()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        problems.add("experiences: " + notCopied.size() + " of " + draft.experiences().size() + " statements are not copied word for word "
                + "from the résumé; copy each bullet exactly as written (you may stop at the end of a sentence) and do not summarise");
        notCopied.stream().limit(3).forEach(text -> problems.add("experiences: not in the résumé: \"" + AgentSupport.shorten(String.valueOf(text), 120) + "\""));
        return problems;
    }

    private static List<ResumeExtraction.FactDraft> facts(List<ResumeDraft.FactDraft> drafts) {
        return drafts.stream().map(fact -> new ResumeExtraction.FactDraft(fact.name(), fact.evidence())).toList();
    }
}
