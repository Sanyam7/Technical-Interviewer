import type { ReactNode } from 'react';

import { cx } from '../../lib/cx';
import { formatScore } from '../../lib/format';
import { BAND_FILL, scoreBand } from '../../lib/scoring';

export interface BarItem {
  key: string;
  label: string;
  /** 0 to 100. */
  value: number;
  /** Small text under the label, such as "4 answers". */
  detail?: ReactNode;
  /** Shown instead of the number when set, for example "n/a". */
  display?: string;
}

interface BarListProps {
  items: BarItem[];
  className?: string;
}

/** Horizontal bars as a list: every row is label + number + bar, so the chart needs no separate text alternative. */
export function BarList({ items, className }: BarListProps) {
  return (
    <ul className={cx('space-y-4', className)}>
      {items.map((item) => (
        <li key={item.key}>
          <div className="flex items-baseline justify-between gap-3">
            <span className="min-w-0 text-sm font-medium wrap-anywhere">{item.label}</span>
            <span className="text-sm font-semibold tabular-nums">
              {item.display ?? formatScore(item.value)}
            </span>
          </div>
          <div
            className="bg-surface-muted mt-1.5 h-2.5 overflow-hidden rounded-full"
            role="presentation"
          >
            <div
              className="h-full rounded-full transition-[width] duration-700 motion-reduce:transition-none"
              style={{
                width: `${Math.min(100, Math.max(0, item.value))}%`,
                backgroundColor: BAND_FILL[scoreBand(item.value)],
              }}
            />
          </div>
          {item.detail ? <p className="text-ink-muted mt-1 text-xs">{item.detail}</p> : null}
        </li>
      ))}
    </ul>
  );
}
