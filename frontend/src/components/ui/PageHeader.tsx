import type { ReactNode } from 'react';

interface PageHeaderProps {
  eyebrow?: string;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}

export function PageHeader({ eyebrow, title, description, action }: PageHeaderProps) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
      <div className="min-w-0">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 className="text-headline mt-1">{title}</h1>
        {description ? (
          <p className="text-ink-muted mt-2 max-w-2xl text-base text-pretty">{description}</p>
        ) : null}
      </div>
      {action}
    </header>
  );
}
