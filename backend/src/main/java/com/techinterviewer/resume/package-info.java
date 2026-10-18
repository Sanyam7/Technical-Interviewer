/**
 * Résumé-aware interviewing (AI_ARCHITECTURE.md section 2.13). A candidate uploads a résumé; {@link
 * com.techinterviewer.resume.parse.ResumeParser} reads its text (the file is never stored), the {@link
 * com.techinterviewer.resume.ResumeExtractor} port turns the text into a draft profile, and {@link
 * com.techinterviewer.resume.ResumeProfileVerifier} keeps only what the résumé really says. The verified profile is the
 * candidate context an interview is planned from: {@link com.techinterviewer.resume.ResumeVocabulary} links its experiences
 * to catalog topics, and the interview engine anchors some questions on them. The model never decides what is asked.
 */
package com.techinterviewer.resume;
