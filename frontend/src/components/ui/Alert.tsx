import type { ReactNode } from 'react';

import { cx } from '../../lib/cx';
import { Icon, type IconName } from './Icon';

type AlertTone = 'info' | 'success' | 'warning' | 'danger';

const TONES: Record<AlertTone, { box: string; icon: IconName }> = {
  info: { box: 'bg-brand-soft text-brand-soft-ink border-brand/25', icon: 'sparkle' },
  success: { box: 'bg-success-bg text-success-ink border-success-solid/30', icon: 'check' },
  warning: { box: 'bg-warning-bg text-warning-ink border-warning-solid/30', icon: 'alert' },
  danger: { box: 'bg-danger-bg text-danger-ink border-danger-solid/30', icon: 'alert' },
};

interface AlertProps {
  tone?: AlertTone;
  title?: string;
  children?: ReactNode;
  action?: ReactNode;
  className?: string;
}

/** A danger alert interrupts (role="alert"); the others are polite status messages. */
export function Alert({ tone = 'info', title, children, action, className }: AlertProps) {
  const { box, icon } = TONES[tone];
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cx('flex items-start gap-3 rounded-xl border p-4', box, className)}
    >
      <Icon name={icon} className="mt-0.5 size-5 shrink-0" />
      <div className="min-w-0 flex-1 text-sm">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children ? <div className={cx(title && 'mt-1')}>{children}</div> : null}
      </div>
      {action}
    </div>
  );
}
