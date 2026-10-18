import type { ReactNode } from 'react';

import { cx } from '../../lib/cx';

export type BadgeTone = 'brand' | 'success' | 'warning' | 'danger' | 'neutral';

const TONES: Record<BadgeTone, string> = {
  brand: 'bg-brand-soft text-brand-soft-ink',
  success: 'bg-success-bg text-success-ink',
  warning: 'bg-warning-bg text-warning-ink',
  danger: 'bg-danger-bg text-danger-ink',
  neutral: 'bg-neutral-bg text-neutral-ink',
};

interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}

export function Badge({ tone = 'neutral', children, className }: BadgeProps) {
  return (
    <span
      data-tone={tone}
      className={cx(
        'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold',
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}
