import { useEffect, useRef, useState } from 'react';

import { DRAFT_PREFIX as PREFIX, markDraftSent, wasDraftSent } from '../../lib/draftStorage';

const SAVE_DELAY_MS = 400;

function read(key: string): string | null {
  try {
    return localStorage.getItem(PREFIX + key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    if (value === '') {
      localStorage.removeItem(PREFIX + key);
    } else {
      localStorage.setItem(PREFIX + key, value);
    }
  } catch {
    // Storage can be full, blocked or absent (private windows): a draft is a convenience, never a requirement.
  }
}

/** Forgets the saved draft of a question (it was sent, or the question is gone). */
export function clearStoredDraft(questionId: string): void {
  markDraftSent(questionId);
  write(questionId, '');
}

/**
 * The text of an answer being written, kept in this browser so a reload or a lost tab does not lose it. It is the only
 * interview data the client keeps; the server owns everything else (ARCHITECTURE.md section 6).
 */
export function useDraft(questionId: string, initial: string) {
  const [value, setValue] = useState(() => read(questionId) ?? initial);
  const [offered, setOffered] = useState(initial);

  // What the editor starts with can arrive after the first render (a follow-up continues from code the transcript has not
  // loaded yet): take it, unless the candidate has already changed what was offered. Adjusted while rendering, not in an effect.
  if (initial !== offered) {
    setOffered(initial);
    if (value === offered) {
      setValue(initial);
    }
  }
  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
    const timer = setTimeout(() => {
      write(questionId, value);
    }, SAVE_DELAY_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [questionId, value]);

  // The editor can close before the delayed save (Submit right after typing, and then the session is lost): save the last text
  // then, unless the answer was sent.
  useEffect(
    () => () => {
      if (!wasDraftSent(questionId)) {
        write(questionId, latest.current);
      }
    },
    [questionId],
  );

  return { value, setValue };
}
