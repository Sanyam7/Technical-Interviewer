import {
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';

import { cx } from '../../lib/cx';

const CONTROL =
  'border-control-border bg-surface text-ink placeholder:text-ink-muted/70 w-full rounded-lg border px-3 text-base shadow-sm transition-colors focus-visible:border-brand disabled:opacity-60 aria-[invalid=true]:border-danger-solid';

interface FieldFrameProps {
  label: string;
  hint?: string;
  error?: string | undefined;
  /** Receives the ids the control must carry. */
  children: (ids: { id: string; describedBy: string | undefined; invalid: boolean }) => ReactNode;
  className?: string;
}

/** Label, hint and error message wired to one control, so a screen reader announces all three. */
function FieldFrame({ label, hint, error, children, className }: FieldFrameProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-sm font-semibold">
        {label}
      </label>
      {children({ id, describedBy: describedBy || undefined, invalid: Boolean(error) })}
      {hint ? (
        <p id={hintId} className="text-ink-muted mt-1.5 text-sm">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-danger-ink mt-1.5 text-sm font-medium">
          {error}
        </p>
      ) : null}
    </div>
  );
}

interface CommonFieldProps {
  label: string;
  hint?: string;
  error?: string | undefined;
  fieldClassName?: string;
}

export function TextField({
  label,
  hint,
  error,
  fieldClassName,
  className,
  ...rest
}: CommonFieldProps & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <FieldFrame label={label} hint={hint} error={error} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <input
          id={id}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          className={cx(CONTROL, 'min-h-11', className)}
          {...rest}
        />
      )}
    </FieldFrame>
  );
}

export function SelectField({
  label,
  hint,
  error,
  fieldClassName,
  className,
  children,
  ...rest
}: CommonFieldProps & SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <FieldFrame label={label} hint={hint} error={error} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <select
          id={id}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          className={cx(CONTROL, 'min-h-11', className)}
          {...rest}
        >
          {children}
        </select>
      )}
    </FieldFrame>
  );
}

export function TextAreaField({
  label,
  hint,
  error,
  fieldClassName,
  className,
  ...rest
}: CommonFieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <FieldFrame label={label} hint={hint} error={error} className={fieldClassName}>
      {({ id, describedBy, invalid }) => (
        <textarea
          id={id}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          className={cx(CONTROL, 'py-2.5 leading-relaxed', className)}
          {...rest}
        />
      )}
    </FieldFrame>
  );
}
