package com.techinterviewer.resume.parse;

import java.time.Duration;

/** The bounds of an uploaded résumé. The first three equal the checks of {@code candidate_resumes} (V13). */
public final class ResumeLimits {

    /** 2 MiB: a résumé is a few pages; a larger file is either not one or mostly images. */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PAGES = 10;
    /** Characters of cleaned text; a two-page résumé has 5,000 to 10,000. */
    public static final int MAX_TEXT_CHARS = 30_000;
    /** A document with fewer letters has no text to speak of (a scan, an image, an empty page). */
    public static final int MIN_LETTERS = 100;
    /** Longest the text of a PDF may take to extract. */
    public static final Duration EXTRACTION_TIME = Duration.ofSeconds(10);
    /** Memory PDFBox may use for the document's decoded streams. */
    static final long MAX_PDF_MEMORY_BYTES = 64L * 1024 * 1024;
    /** Entries a .docx may have, and how large its main part may grow when unpacked (a zip bomb stops here). */
    static final int MAX_ZIP_ENTRIES = 1_000;
    static final int MAX_DOCX_XML_BYTES = 8 * 1024 * 1024;

    private ResumeLimits() {
    }
}
