import { cx } from '../../lib/cx';

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const SIZES = { sm: 'size-4', md: 'size-6', lg: 'size-10' } as const;

/** Decorative: the surrounding text (or a role="status" region) says what is happening. */
export function Spinner({ size = 'md', className }: SpinnerProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      className={cx('animate-spin motion-reduce:animate-none', SIZES[size], className)}
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}
