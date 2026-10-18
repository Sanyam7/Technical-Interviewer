import { cx } from '../../lib/cx';

/** A placeholder block; the page announces loading through a status region, so the blocks are hidden from readers. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cx('bg-surface-muted animate-pulse-soft rounded-lg', className ?? 'h-4 w-full')}
    />
  );
}
