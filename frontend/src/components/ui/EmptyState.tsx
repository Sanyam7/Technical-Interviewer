import type { ReactNode } from 'react';

import { Icon, type IconName } from './Icon';

interface EmptyStateProps {
  icon?: IconName;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({ icon = 'sparkle', title, children, action }: EmptyStateProps) {
  return (
    <div className="border-line flex flex-col items-center rounded-2xl border border-dashed px-6 py-12 text-center">
      <span className="bg-brand-soft text-brand-soft-ink grid size-12 place-items-center rounded-full">
        <Icon name={icon} className="size-6" />
      </span>
      <h3 className="mt-4 text-lg font-semibold tracking-tight">{title}</h3>
      {children ? <div className="text-ink-muted mt-1.5 max-w-md text-sm">{children}</div> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}
