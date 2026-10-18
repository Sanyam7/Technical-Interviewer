import { useId, type ReactNode } from 'react';

import { cx } from '../../lib/cx';

export interface ChoiceOption<T extends string> {
  value: T;
  label: string;
  hint?: ReactNode;
  disabled?: boolean;
}

const CARD =
  'border-line bg-surface hover:border-control-border peer-checked:border-brand peer-checked:bg-brand-soft peer-checked:text-brand-soft-ink peer-focus-visible:outline-brand flex h-full min-h-11 cursor-pointer flex-col justify-center rounded-xl border px-4 py-3 text-sm transition-colors peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-disabled:cursor-not-allowed peer-disabled:opacity-50';

interface ChoiceGroupProps<T extends string> {
  legend: string;
  hint?: string;
  value: T;
  options: ChoiceOption<T>[];
  onChange: (value: T) => void;
  /** Tailwind grid-columns classes for the options. */
  columns?: string;
  error?: string | undefined;
}

/** One answer from several, as a radio group: native inputs underneath, so arrow keys and screen readers work as expected. */
export function ChoiceGroup<T extends string>({
  legend,
  hint,
  value,
  options,
  onChange,
  columns = 'grid-cols-2 sm:grid-cols-3',
  error,
}: ChoiceGroupProps<T>) {
  const name = useId();
  return (
    <fieldset>
      <legend className="mb-1 text-sm font-semibold">{legend}</legend>
      {hint ? <p className="text-ink-muted mb-2.5 text-sm">{hint}</p> : <div className="mb-2" />}
      <div className={cx('grid gap-2.5', columns)}>
        {options.map((option) => (
          <label key={option.value} className="relative block">
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={option.value === value}
              disabled={option.disabled}
              onChange={() => {
                onChange(option.value);
              }}
              className="peer sr-only"
            />
            <span className={CARD}>
              <span className="font-semibold">{option.label}</span>
              {option.hint ? (
                <span className="text-ink-muted mt-0.5 text-xs">{option.hint}</span>
              ) : null}
            </span>
          </label>
        ))}
      </div>
      {error ? (
        <p role="alert" className="text-danger-ink mt-2 text-sm font-medium">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}

interface ChipGroupProps {
  legend: string;
  hint?: string;
  options: { value: string; label: string }[];
  selected: string[];
  onChange: (selected: string[]) => void;
  max?: number;
  error?: string | undefined;
}

/** Several answers from several, as checkboxes; once `max` are chosen the others are disabled instead of silently ignored. */
export function ChipGroup({
  legend,
  hint,
  options,
  selected,
  onChange,
  max,
  error,
}: ChipGroupProps) {
  const full = max !== undefined && selected.length >= max;
  return (
    <fieldset>
      <legend className="mb-1 text-sm font-semibold">{legend}</legend>
      {hint ? <p className="text-ink-muted mb-2.5 text-sm">{hint}</p> : <div className="mb-2" />}
      <div className="flex flex-wrap gap-2.5">
        {options.map((option) => {
          const checked = selected.includes(option.value);
          return (
            <label key={option.value} className="relative block">
              <input
                type="checkbox"
                value={option.value}
                checked={checked}
                disabled={full && !checked}
                onChange={() => {
                  onChange(
                    checked
                      ? selected.filter((value) => value !== option.value)
                      : [...selected, option.value],
                  );
                }}
                className="peer sr-only"
              />
              <span className="border-line bg-surface hover:border-control-border peer-checked:border-brand peer-checked:bg-brand-soft peer-checked:text-brand-soft-ink peer-focus-visible:outline-brand inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-semibold transition-colors peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-disabled:cursor-not-allowed peer-disabled:opacity-45">
                <span
                  aria-hidden="true"
                  className={cx(
                    'grid size-4 place-items-center rounded-full border text-[10px]',
                    checked ? 'border-brand bg-brand text-brand-ink' : 'border-control-border',
                  )}
                >
                  {checked ? '✓' : null}
                </span>
                {option.label}
              </span>
            </label>
          );
        })}
      </div>
      {error ? (
        <p role="alert" className="text-danger-ink mt-2 text-sm font-medium">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}
