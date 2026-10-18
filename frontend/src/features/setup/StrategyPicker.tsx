import type { InterviewStrategy, StageMix, StrategyOption } from '../../api/types';
import { ChoiceGroup } from '../../components/ui/Choice';
import { TextField } from '../../components/ui/Field';
import { STRATEGY_HINT, STRATEGY_LABEL } from '../../lib/labels';
import { describeMix, mixTotal } from './stageMix';

const PARTS = [
  ['fundamentals', 'Fundamentals'],
  ['practical', 'Practical scenarios'],
  ['project', 'Your projects'],
] as const;

interface StrategyPickerProps {
  strategies: StrategyOption[];
  value: InterviewStrategy;
  mix: StageMix;
  hasResume: boolean;
  onChange: (strategy: InterviewStrategy) => void;
  onMixChange: (mix: StageMix) => void;
  /** A server-side error about the strategy or the mix. */
  error: string | undefined;
  showMixErrors: boolean;
}

/**
 * How the technical questions are divided between the fundamentals of each technology, practical scenarios and the candidate's
 * own projects. A strategy that needs a résumé is offered only once one is uploaded; CUSTOM asks for the three shares.
 */
export function StrategyPicker({
  strategies,
  value,
  mix,
  hasResume,
  onChange,
  onMixChange,
  error,
  showMixErrors,
}: StrategyPickerProps) {
  const total = mixTotal(mix);
  return (
    <div className="space-y-4">
      <ChoiceGroup<InterviewStrategy>
        legend="Interview approach"
        hint="How the technical questions are divided between the fundamentals of each technology, practical scenarios and your own projects."
        value={value}
        onChange={onChange}
        error={error}
        columns="grid-cols-1 sm:grid-cols-2"
        options={strategies.map((option) => ({
          value: option.value,
          label: STRATEGY_LABEL[option.value],
          disabled: option.requiresResume && !hasResume,
          hint: (
            <>
              {STRATEGY_HINT[option.value]}
              {option.mix ? (
                <span className="text-ink-muted mt-1 block">{describeMix(option.mix)}</span>
              ) : null}
              {option.requiresResume && !hasResume ? (
                <span className="mt-1 block">Upload a résumé on your profile to choose it.</span>
              ) : null}
            </>
          ),
        }))}
      />
      {value === 'CUSTOM' ? (
        <fieldset>
          <legend className="mb-2 text-sm font-semibold">Shares of the technical questions</legend>
          <div className="grid gap-4 sm:grid-cols-3">
            {PARTS.map(([part, label]) => (
              <TextField
                key={part}
                label={`${label} (%)`}
                type="number"
                inputMode="numeric"
                min={0}
                max={100}
                step={5}
                value={mix[part]}
                onChange={(event) => {
                  const number = Number(event.target.value);
                  onMixChange({
                    ...mix,
                    [part]: Number.isFinite(number)
                      ? Math.max(0, Math.min(100, Math.round(number)))
                      : 0,
                  });
                }}
              />
            ))}
          </div>
          <p
            role={showMixErrors && total !== 100 ? 'alert' : undefined}
            className={
              total === 100
                ? 'text-ink-muted mt-2 text-sm'
                : 'text-danger-ink mt-2 text-sm font-medium'
            }
          >
            {total === 100
              ? 'Total: 100%.'
              : `The shares add up to ${total}%; they must add up to 100%.`}
          </p>
        </fieldset>
      ) : null}
    </div>
  );
}
