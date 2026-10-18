package com.techinterviewer.rag;

/** Ingestion progress of a document. The constants equal the {@code ck_knowledge_documents_status} check. */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
