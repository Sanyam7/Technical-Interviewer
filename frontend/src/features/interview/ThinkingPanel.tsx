import { useEffect, useState, type ReactNode } from 'react';

import { Spinner } from '../../components/ui/Spinner';

interface ThinkingPanelProps {
  title: string;
  children?: ReactNode;
}

/**
 * The AI is working. It says what is being done and how long it has been, and nothing it cannot know (no fake stages): an
 * evaluation and the next question can take a minute or more (API_DESIGN.md section 6.4).
 */
export function ThinkingPanel({ title, children }: ThinkingPanelProps) {
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => {
      setSeconds((count) => count + 1);
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, []);

  return (
    <div
      role="status"
      className="border-line bg-surface shadow-raised flex items-start gap-4 rounded-2xl border p-5 sm:p-6"
    >
      <span className="bg-brand-soft text-brand-soft-ink grid size-11 shrink-0 place-items-center rounded-full">
        <Spinner size="md" />
      </span>
      <div className="min-w-0">
        <p className="font-semibold">
          {title}
          <span className="text-ink-muted ml-2 text-sm font-normal tabular-nums">{seconds}s</span>
        </p>
        <p className="text-ink-muted mt-1 text-sm">
          {children ??
            'This usually takes a few seconds and can take a minute or two. Please keep this page open.'}
        </p>
      </div>
    </div>
  );
}
