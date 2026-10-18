package com.techinterviewer.resume;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Résumé documents built in memory for the tests: real PDFs (PDFBox) and real .docx archives, plus broken variants. */
public final class ResumeFiles {

    /** A realistic résumé. Its contact line must never reach a model. */
    public static final String RESUME = """
            Ada Lovelace
            ada.lovelace@example.com | +1 555 123 4567 | linkedin.com/in/ada-lovelace
            Senior Backend Engineer with 8 years of experience building payment systems.

            Experience
            Senior Software Engineer, Acme Payments - Mar 2021 to Present
            - Built a Spring Boot microservice using Kafka to process card payment events.
            - Reduced p99 latency of the checkout API by 40% with Redis caching.
            - Led the migration of the ledger from MySQL to PostgreSQL with zero downtime.
            Software Engineer, Globex - Jun 2017 to Feb 2021
            - Designed REST APIs for the order service in Java and Spring Boot.
            - Wrote integration tests with Testcontainers and JUnit.

            Projects
            Fraud Rules Engine: a rules engine evaluating 2,000 transactions per second.

            Skills
            Java, Spring Boot, Kafka, PostgreSQL, Redis, Docker, Kubernetes
            """;

    private ResumeFiles() {
    }

    /** A PDF with one page per text; lines become lines. */
    public static byte[] pdf(String... pages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                document.addPage(page(document, text));
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A PDF that opens only with a password. */
    public static byte[] encryptedPdf(String text) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(page(document, text));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static PDPage page(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        if (text.isEmpty()) {
            return page;
        }
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
            content.setLeading(11);
            content.newLineAtOffset(40, 750);
            for (String line : text.split("\n")) {
                content.showText(line);
                content.newLine();
            }
            content.endText();
        }
        return page;
    }

    /** A Word document whose paragraphs are the lines of {@code text}. */
    public static byte[] docx(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(line)).append("</w:t></w:r></w:p>");
        }
        return docxWithDocumentXml(documentXml(body.toString()));
    }

    public static String documentXml(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>";
    }

    /** A .docx archive with the given main part. */
    public static byte[] docxWithDocumentXml(String documentXml) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
