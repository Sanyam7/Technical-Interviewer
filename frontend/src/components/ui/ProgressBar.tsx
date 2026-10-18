import { cx } from '../../lib/cx';

interface ProgressBarProps {
  /** 0 to 100. */
  value: number;
  label: string;
  className?: string;
  tone?: 'brand' | 'success' | 'warning' | 'danger';
}

const FILL = {
  brand: 'bg-brand',
  success: 'bg-success-solid',
  warning: 'bg-warning-solid',
  danger: 'bg-danger-solid',
} as const;

export function ProgressBar({ value, label, className, tone = 'brand' }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(clamped)}
      className={cx('bg-surface-muted h-2 overflow-hidden rounded-full', className)}
    >
      <div
        className={cx('h-full rounded-full transition-[width] duration-500', FILL[tone])}
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}
