package com.techinterviewer.resume;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.resume.domain.CandidateResume;
import com.techinterviewer.resume.domain.ResumeProfile;
import com.techinterviewer.resume.dto.ResumeResponse;
import com.techinterviewer.resume.parse.ParsedResume;
import com.techinterviewer.resume.parse.ResumeParser;
import com.techinterviewer.resume.parse.ResumeText;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A candidate's résumé (AI_ARCHITECTURE.md section 2.13): upload, read, delete, and the profile an interview is planned from.
 * Every operation takes the authenticated user's id and touches only that user's résumé.
 *
 * <p>An upload runs the pipeline <em>file → text → draft profile → verified profile</em>: {@link ResumeParser} (type from the
 * content, limits, cleaning), contact details removed, the {@link ResumeExtractor} port (a model call, outside any transaction),
 * {@link ResumeProfileVerifier}. A document whose profile has nothing verifiable is {@code NOT_A_RESUME}. Only then is the
 * profile stored, replacing the previous résumé. The bytes and the text live only in this request.
 */
@Service
public class ResumeService {

    static final String NOT_A_RESUME_DETAIL =
            "No role, technology or experience could be found in this document. Upload your résumé as a PDF, a Word document or text.";

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private final ResumeRepository resumes;
    private final ResumeExtractor extractor;
    private final ResumeVocabulary vocabulary;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    ResumeService(ResumeRepository resumes, ResumeExtractor extractor, ResumeVocabulary vocabulary, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.resumes = resumes;
        this.extractor = extractor;
        this.vocabulary = vocabulary;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Reads, verifies and stores a résumé, replacing the user's previous one.
     *
     * @throws ApiException 413, 415 {@code UNSUPPORTED_FILE_TYPE}, 422 {@code DOCUMENT_UNREADABLE}, {@code DOCUMENT_EMPTY},
     *                      {@code DOCUMENT_TOO_LONG} or {@code NOT_A_RESUME}; 503 when no model can read it
     */
    public ResumeResponse upload(UUID userId, @Nullable String originalFileName, byte[] bytes) {
        ParsedResume parsed = ResumeParser.parse(bytes);
        String text = ResumeText.withoutContactDetails(parsed.text());
        ResumeExtraction extraction = extractor.extract(userId, text);
        ResumeProfileVerifier.Result verified = ResumeProfileVerifier.verify(extraction, vocabulary, YearMonth.now(clock.withZone(ZoneOffset.UTC)));
        ResumeProfile profile = verified.profile();
        log.info("Résumé of user {}: {} {} bytes, {} experiences, {} technologies, {} items dropped{}", userId, parsed.type(), bytes.length,
                profile.experiences().size(), profile.technologies().size(), verified.dropped().size(),
                extraction.injectionSuspected() ? ", passages addressed to the AI removed" : "");
        if (!profile.hasSubstance()) {
            throw new ApiException(ErrorCode.NOT_A_RESUME, NOT_A_RESUME_DETAIL);
        }
        CandidateResume resume = new CandidateResume(userId, ResumeFileNames.sanitize(originalFileName), parsed.type(), bytes.length,
                parsed.pageCount(), parsed.text().length(), profile, extraction.injectionSuspected(), extraction.llmCallId(),
                extraction.model(), extraction.promptVersion(), clock.instant());
        try {
            return replace(userId, resume);
        } catch (DataIntegrityViolationException concurrentUpload) {
            // Another upload of the same user stored its résumé between our delete and insert: the later one wins.
            return replace(userId, resume);
        }
    }

    private ResumeResponse replace(UUID userId, CandidateResume resume) {
        return writeTransaction.execute(status -> {
            resumes.deleteByUserId(userId);
            return ResumeViews.of(resumes.saveAndFlush(resume));
        });
    }

    /** The user's résumé, if there is one. */
    public Optional<ResumeResponse> find(UUID userId) {
        return readTransaction.execute(status -> resumes.findByUserId(userId).map(ResumeViews::of));
    }

    /** Deletes the user's résumé and its profile; interviews linked to it continue without personalisation. Idempotent. */
    public void delete(UUID userId) {
        writeTransaction.executeWithoutResult(status -> resumes.deleteByUserId(userId));
    }

    /** The user's current résumé, for planning a personalised interview. */
    public Optional<ResumeSnapshot> current(UUID userId) {
        return readTransaction.execute(status -> resumes.findByUserId(userId).map(resume -> new ResumeSnapshot(resume.getId(), resume.getProfile())));
    }

    /** The profile of a résumé an interview is linked to; empty once it was deleted or replaced. */
    public Optional<ResumeProfile> profile(UUID resumeId) {
        return readTransaction.execute(status -> resumes.findById(resumeId).map(CandidateResume::getProfile));
    }
}
