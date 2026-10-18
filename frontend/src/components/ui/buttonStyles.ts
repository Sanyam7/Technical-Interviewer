import { cx } from '../../lib/cx';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'md' | 'sm' | 'lg';

const BASE =
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-lg font-semibold whitespace-nowrap transition-colors select-none disabled:cursor-not-allowed disabled:opacity-55';

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-brand-ink hover:bg-brand-strong shadow-sm border border-transparent',
  secondary: 'bg-surface text-ink border border-line hover:bg-surface-muted shadow-sm',
  ghost: 'text-ink hover:bg-surface-muted border border-transparent',
  danger: 'bg-danger-bg text-danger-ink border border-danger-solid/40 hover:border-danger-solid',
};

const SIZES: Record<ButtonSize, string> = {
  sm: 'min-h-9 px-3 text-sm',
  md: 'min-h-11 px-4 text-sm',
  lg: 'min-h-12 px-6 text-base',
};

export function buttonClasses(variant: ButtonVariant, size: ButtonSize, className?: string) {
  return cx(BASE, VARIANTS[variant], SIZES[size], className);
}
