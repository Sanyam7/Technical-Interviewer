package com.techinterviewer.resume;

import static com.techinterviewer.resume.ResumeFiles.RESUME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.interview.turn.TurnApiTestBase;
import com.techinterviewer.resume.parse.ResumeLimits;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The résumé endpoints through HTTP against the real database (AI_ARCHITECTURE.md section 2.13): a valid résumé, an empty one,
 * a malformed document, an unsupported file, the limits, privacy (what is stored, who can read it) and deletion. The model is a
 * scripted double that behaves honestly; prompt injection through the real agent is {@link ResumeInjectionIT}.
 */
class ResumeApiIT extends TurnApiTestBase {

    private static final String RESUME_PATH = "/profile/resume";

    private ResultActions upload(UUID user, String fileName, String contentType, byte[] content) throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, API + RESUME_PATH)
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .with(as(user)));
    }

    private ResultActions upload(UUID user, byte[] pdf) throws Exception {
        return upload(user, "ada-cv.pdf", "application/pdf", pdf);
    }

    private long storedResumes(UUID user) {
        return jdbc.sql("select count(*) from candidate_resumes where user_id = ?").param(user).query(Long.class).single();
    }

    @Test
    void aValidResumeBecomesAVerifiedProfileAndOnlyThatIsStored() throws Exception {
        UUID ada = newUser();

        JsonNode resume = body(upload(ada, ResumeFiles.pdf(RESUME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("ada-cv.pdf"))
                .andExpect(jsonPath("$.fileType").value("PDF"))
                .andExpect(jsonPath("$.pageCount").value(1))
                .andExpect(jsonPath("$.injectionSuspected").value(false))
                .andExpect(jsonPath("$.profile.yearsOfExperience.years").value(8))
                .andExpect(jsonPath("$.profile.yearsOfExperience.basis").value("STATED"))
                .andExpect(jsonPath("$.profile.experiences[0].id").value("x1"))
                .andExpect(jsonPath("$.profile.experiences[0].text").value("Built a Spring Boot microservice using Kafka to process card payment events."))
                .andExpect(jsonPath("$.profile.experiences[0].technologies[0]").value("Spring Boot")));

        assertThat(resume.at("/profile/experiences").size()).isEqualTo(5);
        assertThat(resume.at("/profile/technologies").findValuesAsString("name")).contains("Java", "Kafka", "PostgreSQL", "Redis", "Kubernetes");
        // The model never saw the contact details, and neither the file nor the text was stored: only the profile and its excerpts.
        assertThat(resumeExtractor.texts()).singleElement().satisfies(text -> assertThat(text)
                .contains("[email] | [phone] | [link]").doesNotContain("ada.lovelace@example.com", "555 123 4567", "linkedin.com"));
        Map<String, Object> row = jdbc.sql("select * from candidate_resumes where user_id = ?").param(ada).query().singleRow();
        assertThat(row.keySet()).doesNotContain("text", "content", "file", "bytes");
        assertThat(row.toString()).doesNotContain("ada.lovelace@example.com", "Ada Lovelace");
        assertThat((Integer) row.get("text_chars")).isBetween(700, RESUME.length());
        getAs(ada, RESUME_PATH).andExpect(status().isOk()).andExpect(jsonPath("$.profile.experiences.length()").value(5));
    }

    @Test
    void wordAndTextDocumentsAreAcceptedWhateverTheirDeclaredType() throws Exception {
        UUID ada = newUser();

        upload(ada, "cv.docx", "application/octet-stream", ResumeFiles.docx(RESUME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("DOCX")).andExpect(jsonPath("$.pageCount").doesNotExist());
        upload(ada, "cv.pdf", "application/pdf", ResumeFiles.utf8(RESUME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("TEXT"));
    }

    @Test
    void anEmptyResumeIsRefusedAndNothingIsStored() throws Exception {
        UUID ada = newUser();

        upload(ada, ResumeFiles.pdf("")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_EMPTY"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("scanned")));
        upload(ada, "empty.txt", "text/plain", new byte[0]).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_EMPTY"));

        assertThat(storedResumes(ada)).isZero();
        assertThat(resumeExtractor.texts()).as("no model call for a document without text").isEmpty();
    }

    @Test
    void aMalformedDocumentIsRefused() throws Exception {
        UUID ada = newUser();
        byte[] pdf = ResumeFiles.pdf(RESUME);

        upload(ada, Arrays.copyOf(pdf, 200)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DOCUMENT_UNREADABLE"));
        upload(ada, ResumeFiles.encryptedPdf(RESUME)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("password")));
        upload(ada, "cv.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ResumeFiles.docxWithDocumentXml("<w:document>")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_UNREADABLE"));

        assertThat(storedResumes(ada)).isZero();
    }

    @Test
    void anUnsupportedFileIsRefusedEvenUnderAResumesNameAndType() throws Exception {
        UUID ada = newUser();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 1, 0};

        upload(ada, "resume.pdf", "application/pdf", png)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"))
                .andExpect(jsonPath("$.detail").value("The file is not a supported document. Upload a PDF, a Word document (.docx) or a plain-text file."));
        assertThat(storedResumes(ada)).isZero();
    }

    @Test
    void aFileOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        UUID ada = newUser();
        byte[] big = new byte[ResumeLimits.MAX_FILE_BYTES + 1];
        Arrays.fill(big, (byte) 'a');

        upload(ada, "big.txt", "text/plain", big).andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(resumeExtractor.texts()).isEmpty();
    }

    @Test
    void aDocumentWithNothingAResumeHoldsIsNotAResume() throws Exception {
        UUID ada = newUser();
        String recipe = "Grandmother's apple pie. Peel and slice six apples, mix them with sugar and cinnamon, and bake them in a buttered "
                + "crust for forty minutes until golden. Serve warm with cream or vanilla ice cream.";

        upload(ada, "recipe.txt", "text/plain", ResumeFiles.utf8(recipe)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOT_A_RESUME"));
        assertThat(storedResumes(ada)).isZero();
    }

    @Test
    void withoutAModelNothingIsStoredAndTheCandidateMayRetry() throws Exception {
        UUID ada = newUser();
        resumeExtractor.unavailable();

        upload(ada, ResumeFiles.pdf(RESUME)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        assertThat(storedResumes(ada)).isZero();
    }

    @Test
    void aResumeIsOnlyEverItsOwnersAndRequiresAuthentication() throws Exception {
        UUID ada = newUser();
        UUID grace = newUser();
        upload(ada, ResumeFiles.pdf(RESUME)).andExpect(status().isOk());

        getAs(grace, RESUME_PATH).andExpect(status().isNotFound());
        deleteAs(grace, RESUME_PATH).andExpect(status().isNoContent());
        assertThat(storedResumes(ada)).isOne();
        mockMvc.perform(multipart(HttpMethod.PUT, API + RESUME_PATH).file(new MockMultipartFile("file", "cv.pdf", "application/pdf",
                ResumeFiles.pdf(RESUME)))).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(API + RESUME_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewUploadReplacesTheResumeAndDeletingItRemovesEverythingDerived() throws Exception {
        UUID ada = newUser();
        upload(ada, ResumeFiles.pdf(RESUME)).andExpect(status().isOk());
        UUID first = jdbc.sql("select id from candidate_resumes where user_id = ?").param(ada).query(UUID.class).single();

        upload(ada, ResumeFiles.utf8(RESUME.replace("8 years", "9 years"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.yearsOfExperience.years").value(9));
        List<UUID> now = jdbc.sql("select id from candidate_resumes where user_id = ?").param(ada).query(UUID.class).list();
        assertThat(now).singleElement().isNotEqualTo(first);

        deleteAs(ada, RESUME_PATH).andExpect(status().isNoContent());
        assertThat(storedResumes(ada)).isZero();
        getAs(ada, RESUME_PATH).andExpect(status().isNotFound());
        deleteAs(ada, RESUME_PATH).andExpect(status().isNoContent());
    }

    @Test
    void theFileFieldIsRequired() throws Exception {
        UUID ada = newUser();

        mockMvc.perform(multipart(HttpMethod.PUT, API + RESUME_PATH).with(as(ada))).andExpect(status().isBadRequest());
    }
}
