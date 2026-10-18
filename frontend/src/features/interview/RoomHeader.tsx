import { Link } from 'react-router';

import type { Session } from '../../api/types';
import { BrandMark } from '../../app/brand';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Icon } from '../../components/ui/Icon';
import { cx } from '../../lib/cx';
import { formatClock } from '../../lib/format';
import {
  DIFFICULTY_LABEL,
  EXPERIENCE_LABEL,
  ROLE_LABEL,
  STATE_LABEL,
  TYPE_LABEL,
} from '../../lib/labels';
import { useCountdown } from './useCountdown';

const FIVE_MINUTES = 300;
const ONE_MINUTE = 60;

function timerAnnouncement(remaining: number | null): string {
  if (remaining === null || remaining === 0) {
    return '';
  }
  if (remaining <= ONE_MINUTE) {
    return 'Less than one minute left.';
  }
  return remaining <= FIVE_MINUTES ? 'Less than five minutes left.' : '';
}

export function Timer({ remainingSeconds }: { remainingSeconds: number | null }) {
  const low = remainingSeconds !== null && remainingSeconds <= FIVE_MINUTES;
  const critical = remainingSeconds !== null && remainingSeconds <= ONE_MINUTE;

  return (
    <>
      <div
        role="timer"
        aria-label="Time remaining"
        className={cx(
          'inline-flex min-h-10 items-center gap-2 rounded-lg border px-3 font-mono text-base font-semibold tabular-nums',
          critical
            ? 'border-danger-solid/50 bg-danger-bg text-danger-ink'
            : low
              ? 'border-warning-solid/50 bg-warning-bg text-warning-ink'
              : 'border-line bg-surface',
        )}
      >
        <Icon name="clock" className="size-4.5" />
        {remainingSeconds === null ? '--:--' : formatClock(remainingSeconds)}
      </div>
      <span role="status" className="sr-only">
        {timerAnnouncement(remainingSeconds)}
      </span>
    </>
  );
}

/** The timer of a session: the only part of the room that re-renders every second. */
function SessionTimer({ session, receivedAt }: { session: Session; receivedAt: number }) {
  const { remainingSeconds } = useCountdown(session, receivedAt);
  return <Timer remainingSeconds={remainingSeconds} />;
}

interface RoomHeaderProps {
  session: Session;
  /** When the session arrived, for the clock offset (useCountdown). */
  receivedAt: number;
  onEnd?: () => void;
  /** An answer is being evaluated: ending now would race with it, so the button waits. */
  endDisabled?: boolean;
}

/** The room's top bar: where you are, what state the interview is in, and the clock. */
export function RoomHeader({ session, receivedAt, onEnd, endDisabled = false }: RoomHeaderProps) {
  const { configuration } = session;
  return (
    <header className="border-line bg-surface/90 sticky top-0 z-30 border-b backdrop-blur">
      <div className="mx-auto flex w-full max-w-7xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:px-6">
        <Link
          to="/dashboard"
          className="hover:bg-surface-muted -ml-1 inline-flex min-h-10 items-center gap-2 rounded-lg px-1 text-sm font-semibold"
          title="Leave the room. The clock keeps running."
        >
          <BrandMark className="size-7" />
          <span className="sr-only sm:not-sr-only">Dashboard</span>
        </Link>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-sm font-semibold sm:text-base">
            {TYPE_LABEL[configuration.interviewType]} interview · {ROLE_LABEL[configuration.role]},{' '}
            {EXPERIENCE_LABEL[configuration.experienceLevel]}
          </h1>
          <p className="text-ink-muted truncate text-xs">
            {DIFFICULTY_LABEL[configuration.difficulty]} difficulty ·{' '}
            {configuration.skills.map((skill) => skill.name).join(', ')}
          </p>
        </div>
        <Badge tone={session.state === 'FOLLOW_UP' ? 'warning' : 'brand'}>
          <span className="sr-only">Interview state: </span>
          {STATE_LABEL[session.state]}
        </Badge>
        <SessionTimer session={session} receivedAt={receivedAt} />
        {onEnd ? (
          <Button variant="secondary" size="sm" onClick={onEnd} disabled={endDisabled}>
            End interview
          </Button>
        ) : null}
      </div>
    </header>
  );
}
