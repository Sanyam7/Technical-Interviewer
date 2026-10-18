package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class InterviewOptionsTest {

    @Test
    void durationsAreExactlyTheOnesTheDatabaseAccepts() throws IOException {
        String migration = new String(
                new ClassPathResource("db/migration/V6__interviews.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher check = Pattern.compile("ck_interview_configurations_duration CHECK \\(duration_minutes IN \\(([^)]*)\\)\\)").matcher(migration);
        assertThat(check.find()).as("the duration check exists in V6").isTrue();
        List<Integer> allowed = new ArrayList<>();
        Arrays.stream(check.group(1).split(",")).map(String::trim).map(Integer::valueOf).forEach(allowed::add);

        assertThat(InterviewOptions.DURATIONS_MINUTES).containsExactlyElementsOf(allowed);
    }

    @Test
    void durationsAndLanguagesAreTheDocumentedOnesInOrder() {
        assertThat(InterviewOptions.DURATIONS_MINUTES).containsExactly(15, 30, 45, 60, 90);
        assertThat(InterviewOptions.CODING_LANGUAGES).containsExactly("java", "javascript", "typescript", "python", "go");
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 30, 45, 60, 90})
    void supportedDurationsAreAccepted(int minutes) {
        assertThat(InterviewOptions.isSupportedDuration(minutes)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -15, 0, 1, 14, 16, 29, 31, 44, 59, 61, 75, 89, 91, 120, Integer.MAX_VALUE})
    void otherDurationsAreRejected(int minutes) {
        assertThat(InterviewOptions.isSupportedDuration(minutes)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "javascript", "typescript", "python", "go"})
    void supportedLanguagesAreAccepted(String language) {
        assertThat(InterviewOptions.isSupportedCodingLanguage(language)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Java", "JAVA", "java ", "JavaScript", "js", "rust", "c++", "kotlin"})
    void otherLanguagesAreRejectedAndMatchingIsCaseSensitive(String language) {
        assertThat(InterviewOptions.isSupportedCodingLanguage(language)).isFalse();
    }

    @Test
    void everyInterviewTypeRunsTheSectionsOfTheDesign() {
        assertThat(InterviewOptions.sectionsOf(InterviewType.TECHNICAL)).containsExactly(Section.INTRODUCTION, Section.QUESTIONING);
        assertThat(InterviewOptions.sectionsOf(InterviewType.CODING)).containsExactly(Section.INTRODUCTION, Section.CODING);
        assertThat(InterviewOptions.sectionsOf(InterviewType.SYSTEM_DESIGN)).containsExactly(Section.INTRODUCTION, Section.SYSTEM_DESIGN);
        assertThat(InterviewOptions.sectionsOf(InterviewType.FULL_LOOP))
                .containsExactly(Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
    }

    @ParameterizedTest
    @EnumSource(InterviewType.class)
    void sectionsStartWithTheIntroductionAndFollowTheDeclarationOrderOfSection(InterviewType type) {
        List<Section> sections = InterviewOptions.sectionsOf(type);

        assertThat(sections).first().isEqualTo(Section.INTRODUCTION);
        assertThat(sections).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.comparing(Section::ordinal));
    }
}
