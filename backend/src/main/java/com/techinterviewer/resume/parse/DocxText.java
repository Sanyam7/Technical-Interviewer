package com.techinterviewer.resume.parse;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The text of a Word document (.docx), read with the JDK alone: a .docx is a zip archive whose main part,
 * {@code word/document.xml}, holds the paragraphs. Only that part is read (headers and footers, where contact details usually
 * sit, are not). Bounded against hostile archives: at most {@link ResumeLimits#MAX_ZIP_ENTRIES} entries, and the main part
 * may unpack to at most {@link ResumeLimits#MAX_DOCX_XML_BYTES} (a zip bomb stops there). The XML reader does not process
 * DTDs or external entities, so an XXE or entity-expansion payload cannot read files or exhaust memory.
 */
final class DocxText {

    private static final String MAIN_PART = "word/document.xml";
    private static final String WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final XMLInputFactory XML = secureFactory();

    private DocxText() {
    }

    /** The text of the document; 415 when the archive is not a Word document, 422 when it is damaged. */
    static String extract(byte[] bytes) {
        byte[] document = mainPart(bytes);
        try {
            return text(document);
        } catch (XMLStreamException | RuntimeException malformed) {
            throw new ApiException(ErrorCode.DOCUMENT_UNREADABLE, "The Word document is damaged and cannot be read. Save it again and upload the new file.");
        }
    }

    private static byte[] mainPart(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > ResumeLimits.MAX_ZIP_ENTRIES) {
                    throw new ApiException(ErrorCode.DOCUMENT_UNREADABLE, "The Word document has too many parts to be a résumé.");
                }
                if (MAIN_PART.equals(entry.getName())) {
                    return bounded(zip);
                }
            }
        } catch (ApiException rejected) {
            throw rejected;
        } catch (IOException | RuntimeException malformed) {
            throw new ApiException(ErrorCode.DOCUMENT_UNREADABLE, "The file looks like a Word document but is damaged. Save it again and upload the new file.");
        }
        // A zip archive without the main part: a spreadsheet, a presentation or any other archive.
        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "The file is an archive but not a Word document. " + ResumeParser.SUPPORTED);
    }

    private static byte[] bounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
            if (out.size() > ResumeLimits.MAX_DOCX_XML_BYTES) {
                throw new ApiException(ErrorCode.DOCUMENT_UNREADABLE, "The Word document unpacks to far more than a résumé holds.");
            }
        }
        return out.toByteArray();
    }

    /** Paragraphs become lines; tabs and breaks are kept; deleted text and field codes are not text. */
    private static String text(byte[] xml) throws XMLStreamException {
        XMLStreamReader reader = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder text = new StringBuilder();
        try {
            boolean inText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && WORD_NAMESPACE.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> text.append('\t');
                        case "br", "cr" -> text.append('\n');
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && WORD_NAMESPACE.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = false;
                        case "p" -> text.append('\n');
                        case "tc" -> text.append('\t');
                        default -> {
                        }
                    }
                } else if (inText && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.ENTITY_REFERENCE || event == XMLStreamConstants.DTD) {
                    // A Word document never declares a DTD or uses custom entities: such a part is crafted, not saved by Word.
                    throw new XMLStreamException("DTDs and entity references are not accepted");
                }
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }
}
