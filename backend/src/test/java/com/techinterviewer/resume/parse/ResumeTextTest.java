package com.techinterviewer.resume.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumeTextTest {

    @Test
    void ligaturesBecomeLettersAndInvisibleCharactersAreRemoved() {
        String raw = "Proﬁcient in Java​ and Kafka‮­.\u0007 Icon";

        assertThat(ResumeText.clean(raw)).isEqualTo("Proficient in Java and Kafka. Icon");
    }

    @Test
    void lineBreaksAreUnifiedAndBlankRunsCollapsed() {
        String raw = "Ada\r\n\r\n\r\n\r\nEngineer   at\tAcme \f Projects Done";

        assertThat(ResumeText.clean(raw)).isEqualTo("Ada\n\nEngineer at Acme\nProjects\nDone");
    }

    @Test
    void contactDetailsNeverReachAModel() {
        String text = "ada.lovelace@example.com | +1 (555) 123-4567 | +44 20 7946 0958 | linkedin.com/in/ada | https://ada.dev/cv | www.ada.io";

        assertThat(ResumeText.withoutContactDetails(text))
                .isEqualTo("[email] | [phone] | [phone] | [link] | [link] | [link]");
    }

    @Test
    void datesFiguresAndYearRangesAreNotPhoneNumbers() {
        String text = "2017 - 2021, 2019-03 to 2021-02, 2,000 transactions per second, 99.95% uptime, 12/2019 - 04/2023, v1.2.3";

        assertThat(ResumeText.withoutContactDetails(text)).isEqualTo(text);
    }

    @Test
    void lettersAreCounted() {
        assertThat(ResumeText.letters("Java 21, ok!")).isEqualTo(6);
    }
}
