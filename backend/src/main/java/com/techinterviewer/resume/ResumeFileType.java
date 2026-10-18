package com.techinterviewer.resume;

/** The kinds of document a résumé may be, decided from the content of the upload, never from its name or declared type. */
public enum ResumeFileType {
    PDF,
    /** A Word document in the Office Open XML format (.docx). */
    DOCX,
    /** Plain text or Markdown, UTF-8. */
    TEXT
}
