package com.techinterviewer.catalog.dto;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Everything the interview setup wizard offers. */
@Schema(description = "Choices of an interview configuration.")
public record OptionsResponse(
        List<TargetRole> roles,
        List<ExperienceLevel> experienceLevels,
        List<InterviewDifficulty> difficulties,
        List<Integer> durationsMinutes,
        List<InterviewTypeOption> interviewTypes,
        List<String> codingLanguages,
        @Schema(description = "Coding languages whose solutions this server runs against tests; the others are reviewed by the AI only. "
                + "Empty when code execution is off.", example = "[\"java\", \"javascript\", \"typescript\"]") List<String> executableLanguages,
        @Schema(description = "How the technical questions can be divided between fundamentals, practical scenarios and the candidate's "
                + "own projects; the first is the default") List<StrategyOption> strategies) {

    public OptionsResponse {
        roles = List.copyOf(roles);
        experienceLevels = List.copyOf(experienceLevels);
        difficulties = List.copyOf(difficulties);
        durationsMinutes = List.copyOf(durationsMinutes);
        interviewTypes = List.copyOf(interviewTypes);
        codingLanguages = List.copyOf(codingLanguages);
        executableLanguages = List.copyOf(executableLanguages);
        strategies = List.copyOf(strategies);
    }

    /**
     * An interview strategy.
     *
     * @param value          the strategy
     * @param mix            its stage shares in percent; null for CUSTOM, whose shares the candidate chooses
     * @param requiresResume the strategy needs a résumé (useResume)
     */
    public record StrategyOption(InterviewStrategy value, @Nullable StageShares mix, boolean requiresResume) {
    }

    /** Stage shares in whole percent, adding up to 100. */
    public record StageShares(int fundamentals, int practical, int project) {
    }

    /** An interview type and the sections it runs, in order. */
    public record InterviewTypeOption(InterviewType value, List<Section> sections) {

        public InterviewTypeOption {
            sections = List.copyOf(sections);
        }
    }
}
