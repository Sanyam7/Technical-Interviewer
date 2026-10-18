import { useState, type ReactNode } from 'react';

import { Button } from '../../components/ui/Button';
import { Dialog } from '../../components/ui/Dialog';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { cx } from '../../lib/cx';

interface AnswerBarProps {
  length: number;
  limit: number;
  /** False while the answer is blank or over the limit. */
  canSubmit: boolean;
  submitting: boolean;
  error: unknown;
  onSubmit: () => void;
  /** Repeats the command that failed (the answer or the skip). */
  onRetry: () => void;
  onSkip: () => void;
  skipping: boolean;
  /** The question is a follow-up: skipping it adds nothing (API_DESIGN.md section 6.4). */
  isFollowUp: boolean;
  hint?: ReactNode;
  /** Another action before Skip, such as "Run examples". */
  secondaryAction?: ReactNode;
}

/** Submit, skip and the character budget: the same under every kind of answer. */
export function AnswerBar({
  length,
  limit,
  canSubmit,
  submitting,
  error,
  onSubmit,
  onRetry,
  onSkip,
  skipping,
  isFollowUp,
  hint,
  secondaryAction,
}: AnswerBarProps) {
  const [confirmSkip, setConfirmSkip] = useState(false);
  const over = length > limit;
  const nearLimit = length > limit * 0.9;

  return (
    <div className="space-y-4">
      {error !== null && error !== undefined ? (
        <ErrorState error={error} onRetry={onRetry} retrying={submitting || skipping} />
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p
          className={cx(
            'text-sm tabular-nums',
            over
              ? 'text-danger-ink font-semibold'
              : nearLimit
                ? 'text-warning-ink'
                : 'text-ink-muted',
          )}
        >
          {length.toLocaleString()} / {limit.toLocaleString()} characters
          {over ? ' · too long' : ''}
        </p>
        <div className="flex flex-wrap items-center gap-3">
          {secondaryAction}
          <Button
            variant="ghost"
            onClick={() => {
              setConfirmSkip(true);
            }}
            disabled={submitting || skipping}
          >
            Skip
          </Button>
          <Button
            onClick={onSubmit}
            disabled={!canSubmit}
            loading={submitting}
            icon={<Icon name="arrow-right" className="order-last size-4" />}
          >
            Submit answer
          </Button>
        </div>
      </div>
      {hint ? <p className="text-ink-muted text-xs">{hint}</p> : null}

      <Dialog
        open={confirmSkip}
        onClose={() => {
          setConfirmSkip(false);
        }}
        title="Skip this question?"
        description={
          isFollowUp
            ? 'A skipped follow-up adds nothing to your score, and the interview moves on.'
            : 'A skipped question counts as zero in your score, and the interview moves on.'
        }
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setConfirmSkip(false);
              }}
            >
              Keep answering
            </Button>
            <Button
              variant="danger"
              loading={skipping}
              onClick={() => {
                setConfirmSkip(false);
                onSkip();
              }}
            >
              Skip question
            </Button>
          </>
        }
      />
    </div>
  );
}
