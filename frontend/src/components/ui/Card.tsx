import { useId, type ReactNode } from 'react';

import { cx } from '../../lib/cx';

interface CardProps {
  title: string;
  /** Short line under the title. */
  description?: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}

/** A titled region of a page: the title names the landmark, so assistive technology can jump between cards. */
export function Card({ title, description, action, className, children }: CardProps) {
  const titleId = useId();

  return (
    <section
      aria-labelledby={titleId}
      className={cx(
        'border-line bg-surface rounded-2xl border p-5 shadow-raised sm:p-6',
        className,
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id={titleId} className="text-lg font-semibold tracking-tight">
            {title}
          </h2>
          {description ? <p className="text-ink-muted mt-1 text-sm">{description}</p> : null}
        </div>
        {action}
      </div>
      <div className="mt-5">{children}</div>
    </section>
  );
}

/** A plain raised surface for content that has no heading of its own. */
export function Panel({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cx('border-line bg-surface rounded-2xl border p-5 shadow-raised', className)}>
      {children}
    </div>
  );
}
