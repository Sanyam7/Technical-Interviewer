package com.techinterviewer.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.resume.parse.ResumeParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The labels of the résumé evaluation set are consistent with their résumés (the live test measures the model against them). */
class ResumeEvaluationDatasetTest {

    private final List<ResumeEvaluationCase> cases = ResumeEvaluationCase.load();

    @Test
    void everyCaseParsesAndItsLabelsRestOnItsText() {
        assertThat(cases).hasSizeGreaterThanOrEqualTo(5);
        Set<String> ids = new HashSet<>();
        for (ResumeEvaluationCase testCase : cases) {
            assertThat(ids.add(testCase.id())).as("unique id " + testCase.id()).isTrue();
            String text = ResumeParser.parse(testCase.resume().getBytes(StandardCharsets.UTF_8)).text();
            testCase.expected().experiences().forEach(statement -> assertThat(text).as(testCase.id()).contains(statement));
            testCase.expected().mustNotContain().forEach(forbidden -> assertThat(text).as(testCase.id()).contains(forbidden));
            if (testCase.expected().statedYears() != null) {
                assertThat(text).as(testCase.id()).contains(testCase.expected().statedYears() + "");
            }
            if (!testCase.expected().substance()) {
                assertThat(testCase.expected().experiences()).isEmpty();
            }
        }
    }

    @Test
    void theHonestScriptedExtractorMeetsTheLabelsThatDoNotNeedAModel() {
        // The scripted double copies bullets and vocabulary technologies: the verifier must keep all of that and nothing injected.
        for (ResumeEvaluationCase testCase : cases) {
            String text = ResumeParser.parse(testCase.resume().getBytes(StandardCharsets.UTF_8)).text();
            var profile = ResumeProfileVerifier.verify(ScriptedResumeExtractor.honest(text), ResumeVocabulary.load(), java.time.YearMonth.of(2026, 10))
                    .profile();
            assertThat(profile.technologies().stream().map(com.techinterviewer.resume.domain.ResumeFact::name).toList()).as(testCase.id())
                    .containsAll(testCase.expected().technologies().stream().filter(name -> ResumeVocabulary.load().technology(name).isPresent()).toList());
        }
    }
}
