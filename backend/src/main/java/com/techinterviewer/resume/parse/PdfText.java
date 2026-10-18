package com.techinterviewer.resume.parse;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * The text of a PDF (PDFBox), bounded in every dimension a hostile file could abuse: the decoded streams live in memory up to
 * {@link ResumeLimits#MAX_PDF_MEMORY_BYTES} (never in temporary files), at most {@link ResumeLimits#MAX_PAGES} pages, the
 * extraction stops once the text is far beyond what a résumé may hold or {@link ResumeLimits#EXTRACTION_TIME} has passed.
 * Password-protected and encrypted documents are refused: they cannot be read reliably and a résumé has no reason to be.
 */
final class PdfText {

    /** Raw characters extracted before the text is certainly too long, even after cleaning shortens it. */
    private static final int MAX_RAW_CHARS = 3 * ResumeLimits.MAX_TEXT_CHARS;

    private PdfText() {
    }

    /** The extracted text and the page count. */
    record Extracted(String text, int pages) {
    }

    static Extracted extract(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(ResumeLimits.MAX_PDF_MEMORY_BYTES).streamCache)) {
            if (document.isEncrypted()) {
                throw unreadable("The PDF is encrypted or password-protected. Upload an unprotected copy.");
            }
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new ApiException(ErrorCode.DOCUMENT_EMPTY, "The PDF has no pages.");
            }
            if (pages > ResumeLimits.MAX_PAGES) {
                throw new ApiException(ErrorCode.DOCUMENT_TOO_LONG,
                        "The PDF has " + pages + " pages; a résumé may have at most " + ResumeLimits.MAX_PAGES + ".");
            }
            BoundedStripper stripper = new BoundedStripper(System.nanoTime() + ResumeLimits.EXTRACTION_TIME.toNanos());
            stripper.setSortByPosition(true);
            return new Extracted(stripper.getText(document), pages);
        } catch (ApiException rejected) {
            throw rejected;
        } catch (TooLong tooLong) {
            throw new ApiException(ErrorCode.DOCUMENT_TOO_LONG,
                    "The PDF holds far more text than a résumé (at most " + ResumeLimits.MAX_TEXT_CHARS + " characters).");
        } catch (TookTooLong slow) {
            throw unreadable("Reading the PDF took too long. Export it again or upload it as a Word or text file.");
        } catch (InvalidPasswordException password) {
            throw unreadable("The PDF is password-protected. Upload an unprotected copy.");
        } catch (IOException | RuntimeException malformed) {
            // PDFBox reports a damaged file as an IOException, and some malformed structures as runtime exceptions.
            throw unreadable("The PDF is damaged and cannot be read. Export it again and upload the new file.");
        }
    }

    private static ApiException unreadable(String detail) {
        return new ApiException(ErrorCode.DOCUMENT_UNREADABLE, detail);
    }

    private static final class TooLong extends RuntimeException {
        TooLong() {
            super(null, null, false, false);
        }
    }

    private static final class TookTooLong extends RuntimeException {
        TookTooLong() {
            super(null, null, false, false);
        }
    }

    /** Stops the extraction once the deadline has passed or the text is certainly too long. */
    private static final class BoundedStripper extends PDFTextStripper {

        private final long deadline;
        private long characters;

        BoundedStripper(long deadline) {
            this.deadline = deadline;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            checkTime();
            super.startPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            characters += text.getUnicode() == null ? 0 : text.getUnicode().length();
            if (characters > MAX_RAW_CHARS) {
                throw new TooLong();
            }
            if ((characters & 0xFF) == 0) {
                checkTime();
            }
            super.processTextPosition(text);
        }

        private void checkTime() {
            if (System.nanoTime() - deadline > 0) {
                throw new TookTooLong();
            }
        }
    }
}
