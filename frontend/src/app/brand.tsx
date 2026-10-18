import { Link } from 'react-router';

import { cx } from '../lib/cx';

export const PRODUCT_NAME = 'AI Technical Interviewer';

/** The mark: a rounded tile with a prompt chevron and cursor. Decorative, the wordmark next to it names the product. */
export function BrandMark({ className = 'size-8' }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 32 32" className={className}>
      <defs>
        <linearGradient id="brand-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#6366f1" />
          <stop offset="1" stopColor="#7c3aed" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#brand-mark)" />
      <path
        d="m9.5 11.5 5 4.5-5 4.5"
        fill="none"
        stroke="#fff"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <rect x="17.5" y="19.2" width="6" height="2.4" rx="1.2" fill="#fff" />
    </svg>
  );
}

export function Wordmark({ to = '/', className }: { to?: string; className?: string }) {
  return (
    <Link
      to={to}
      className={cx('inline-flex items-center gap-2.5 font-semibold tracking-tight', className)}
    >
      <BrandMark />
      <span>{PRODUCT_NAME}</span>
    </Link>
  );
}
