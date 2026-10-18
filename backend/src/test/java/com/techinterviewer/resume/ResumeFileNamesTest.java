package com.techinterviewer.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumeFileNamesTest {

    @Test
    void onlyTheLastPathSegmentIsKept() {
        assertThat(ResumeFileNames.sanitize("C:\\Users\\ada\\Desktop\\cv.pdf")).isEqualTo("cv.pdf");
        assertThat(ResumeFileNames.sanitize("../../etc/passwd")).isEqualTo("passwd");
    }

    @Test
    void controlCharactersMarkupAndLeadingDotsAreRemoved() {
        assertThat(ResumeFileNames.sanitize("..hidden<script>\u202Efdp.exe\n.pdf")).isEqualTo("hidden_script_fdp.exe_.pdf");
        assertThat(ResumeFileNames.sanitize("Ada Lovelace (CV) — 2026.pdf")).isEqualTo("Ada Lovelace (CV) _ 2026.pdf");
    }

    @Test
    void anEmptyOrMissingNameGetsADefaultAndALongOneIsCut() {
        assertThat(ResumeFileNames.sanitize(null)).isEqualTo("resume");
        assertThat(ResumeFileNames.sanitize("....")).isEqualTo("resume");
        assertThat(ResumeFileNames.sanitize("a".repeat(500) + ".pdf")).hasSize(120);
    }
}
