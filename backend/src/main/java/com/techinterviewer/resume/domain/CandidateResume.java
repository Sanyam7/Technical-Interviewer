package com.techinterviewer.resume.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.resume.ResumeFileType;
import com.techinterviewer.resume.parse.ResumeLimits;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A candidate's résumé as the interview uses it: the verified profile and facts about the upload, never the file or its text
 * ({@code candidate_resumes}, V13). Immutable: a new upload replaces the row with a new one (a new id), so an interview that was
 * personalised from the old résumé loses its link instead of silently reading a different profile.
 */
@Entity
@Table(name = "candidate_resumes")
public class CandidateResume extends BaseEntity {

    public static final int MAX_FILE_NAME_LENGTH = 120;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false, updatable = false, length = MAX_FILE_NAME_LENGTH)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, updatable = false)
    private ResumeFileType fileType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "page_count", updatable = false)
    private @Nullable Integer pageCount;

    @Column(name = "text_chars", nullable = false, updatable = false)
    private int textChars;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", columnDefinition = "jsonb", nullable = false, updatable = false)
    private ResumeProfile profile;

    @Column(name = "injection_suspected", nullable = false, updatable = false)
    private boolean injectionSuspected;

    @Column(name = "extraction_llm_call_id", updatable = false)
    private @Nullable UUID extractionLlmCallId;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private String promptVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CandidateResume() {
    }

    public CandidateResume(UUID userId, String fileName, ResumeFileType fileType, int sizeBytes, @Nullable Integer pageCount, int textChars,
            ResumeProfile profile, boolean injectionSuspected, @Nullable UUID extractionLlmCallId, String model, String promptVersion,
            Instant now) {
        this.userId = Require.nonNull(userId, "userId");
        this.fileName = Require.notBlank(fileName, "fileName", MAX_FILE_NAME_LENGTH);
        this.fileType = Require.nonNull(fileType, "fileType");
        this.sizeBytes = Require.between(sizeBytes, 1, ResumeLimits.MAX_FILE_BYTES, "sizeBytes");
        if (pageCount != null) {
            Require.between(pageCount, 1, ResumeLimits.MAX_PAGES, "pageCount");
        }
        this.pageCount = pageCount;
        this.textChars = Require.between(textChars, 1, ResumeLimits.MAX_TEXT_CHARS, "textChars");
        this.profile = Require.nonNull(profile, "profile");
        this.injectionSuspected = injectionSuspected;
        this.extractionLlmCallId = extractionLlmCallId;
        this.model = Require.notBlank(model, "model", 80);
        this.promptVersion = Require.notBlank(promptVersion, "promptVersion", 20);
        this.createdAt = Require.nonNull(now, "now");
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFileName() {
        return fileName;
    }

    public ResumeFileType getFileType() {
        return fileType;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public @Nullable Integer getPageCount() {
        return pageCount;
    }

    public int getTextChars() {
        return textChars;
    }

    public ResumeProfile getProfile() {
        return profile;
    }

    public boolean isInjectionSuspected() {
        return injectionSuspected;
    }

    public @Nullable UUID getExtractionLlmCallId() {
        return extractionLlmCallId;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
