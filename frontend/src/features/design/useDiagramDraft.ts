import { useEffect, useState } from 'react';

import type { Architecture, TranscriptEntry } from '../../api/types';
import { DIAGRAM_PREFIX as PREFIX } from '../../lib/draftStorage';
import { EMPTY } from './architecture';

function read(interviewId: string): Architecture | null {
  try {
    const stored = localStorage.getItem(PREFIX + interviewId);
    if (!stored) {
      return null;
    }
    const parsed = JSON.parse(stored) as Architecture;
    return Array.isArray(parsed.components) && Array.isArray(parsed.connections) ? parsed : null;
  } catch {
    return null;
  }
}

function write(interviewId: string, diagram: Architecture): void {
  try {
    if (diagram.components.length === 0) {
      localStorage.removeItem(PREFIX + interviewId);
    } else {
      localStorage.setItem(PREFIX + interviewId, JSON.stringify(diagram));
    }
  } catch {
    // A draft is a convenience: storage may be full, blocked or absent.
  }
}

/** The latest diagram the candidate already sent in this interview, from the transcript. */
export function latestSubmitted(
  entries: readonly TranscriptEntry[] | undefined,
): Architecture | undefined {
  for (const entry of [...(entries ?? [])].reverse()) {
    if (entry.question.section === 'SYSTEM_DESIGN' && entry.answer?.architecture) {
      return entry.answer.architecture;
    }
  }
  return undefined;
}

/**
 * The diagram of the design discussion. It carries over from one answer to the next (a probe usually changes the design a
 * little), so it is kept per interview in this browser; without a stored draft it starts from the last diagram the candidate sent.
 */
export function useDiagramDraft(interviewId: string, submitted: Architecture | undefined) {
  const [diagram, setDiagram] = useState<Architecture>(
    () => read(interviewId) ?? submitted ?? EMPTY,
  );
  const [seen, setSeen] = useState(submitted);

  // The transcript may arrive after the first render: adopt the sent diagram when it does, unless the candidate started drawing.
  // Adjusting state while rendering (not in an effect) avoids a render with the stale diagram.
  if (submitted !== seen) {
    setSeen(submitted);
    if (submitted && diagram.components.length === 0) {
      setDiagram(submitted);
    }
  }

  useEffect(() => {
    write(interviewId, diagram);
  }, [interviewId, diagram]);

  return [diagram, setDiagram] as const;
}
