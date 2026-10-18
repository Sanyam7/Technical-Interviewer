/**
 * The browser keeps two kinds of text between visits: answers being written and architecture diagrams being drawn, so a reload
 * does not lose them (ARCHITECTURE.md section 6). They belong to whoever was signed in, so signing out removes them
 * (SECURITY.md, WEB-3): on a shared computer the next person finds nothing of the previous candidate's answers.
 */
export const DRAFT_PREFIX = 'ti.draft.';
export const DIAGRAM_PREFIX = 'ti.diagram.';

/** Questions whose answer was sent from this page: a closing editor must not write their draft back. */
const sentQuestions = new Set<string>();

export function markDraftSent(questionId: string): void {
  sentQuestions.add(questionId);
}

export function wasDraftSent(questionId: string): boolean {
  return sentQuestions.has(questionId);
}

/** Removes every saved draft. Never throws: storage may be blocked or absent. */
export function clearSavedDrafts(): void {
  sentQuestions.clear();
  try {
    for (let index = localStorage.length - 1; index >= 0; index--) {
      const key = localStorage.key(index);
      if (key !== null && (key.startsWith(DRAFT_PREFIX) || key.startsWith(DIAGRAM_PREFIX))) {
        localStorage.removeItem(key);
      }
    }
  } catch {
    // Nothing to remove, or no way to remove it.
  }
}
