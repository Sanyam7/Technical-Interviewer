import type { ReactNode } from 'react';

type StatusTone = 'success' | 'danger' | 'neutral';

const TONE_CLASSES: Record<StatusTone, string> = {
  success: 'bg-success-bg text-success-ink',
  danger: 'bg-danger-bg text-danger-ink',
  neutral: 'bg-neutral-bg text-neutral-ink',
};

const TONE_MARKS: Record<StatusTone, string> = {
  success: '✓',
  danger: '✕',
  neutral: '•',
};

interface StatusBadgeProps {
  tone: StatusTone;
  children: ReactNode;
}

export function StatusBadge({ tone, children }: StatusBadgeProps) {
  return (
    <span
      data-tone={tone}
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-sm font-semibold ${TONE_CLASSES[tone]}`}
    >
      <span aria-hidden="true">{TONE_MARKS[tone]}</span>
      {children}
    </span>
  );
}
