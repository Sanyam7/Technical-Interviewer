import { useEffect, useState } from 'react';

import { Markdown } from '../../components/ui/Markdown';
import { Button } from '../../components/ui/Button';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

const TICK_MS = 16;
/** A reveal takes about this long however long the text is. */
const TARGET_TICKS = 150;

interface StreamedTextProps {
  text: string;
  /** Reveal progressively; false shows everything at once (a question restored after a reload is not "arriving"). */
  animate: boolean;
}

/**
 * Reveals text progressively. The backend returns the interviewer's whole next question in one response (there is no token
 * stream; ARCHITECTURE.md section 10, 2026-10-11), so this is a presentation effect: it can be skipped, it is off under
 * `prefers-reduced-motion`, and the complete text is always in the page for assistive technology. Mount it with
 * `key={text}` so a new text starts again.
 */
export function StreamedText({ text, animate }: StreamedTextProps) {
  const reducedMotion = usePrefersReducedMotion();
  const reveal = animate && !reducedMotion && text.length > 0;
  const [shown, setShown] = useState(reveal ? 0 : text.length);
  const done = shown >= text.length;

  useEffect(() => {
    if (!reveal) {
      return;
    }
    const step = Math.max(2, Math.ceil(text.length / TARGET_TICKS));
    const timer = setInterval(() => {
      setShown((count) => {
        if (count + step >= text.length) {
          clearInterval(timer);
          return text.length;
        }
        return count + step;
      });
    }, TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, [reveal, text]);

  return (
    <div>
      {done ? (
        <Markdown>{text}</Markdown>
      ) : (
        <>
          <div aria-hidden="true">
            <Markdown>{text.slice(0, shown)}</Markdown>
          </div>
          <p className="sr-only">{text}</p>
          <Button
            variant="ghost"
            size="sm"
            className="mt-2 -ml-2"
            onClick={() => {
              setShown(text.length);
            }}
          >
            Show full question
          </Button>
        </>
      )}
    </div>
  );
}
