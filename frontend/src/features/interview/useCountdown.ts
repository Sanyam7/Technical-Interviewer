import { useCallback, useMemo, useSyncExternalStore } from 'react';

import type { Session } from '../../api/types';

export interface Countdown {
  /** Whole seconds left on the server's clock; null before the interview started. */
  remainingSeconds: number | null;
  expired: boolean;
}

function nowInSeconds(): number {
  return Math.round(Date.now() / 1000);
}

/**
 * The seconds left, read once per second while a clock runs, or null when none runs. The value React compares is the
 * snapshot itself, so a component re-renders only when what it reads changes: every second for the remaining seconds, once
 * for whether the time is up.
 */
function useServerClock<T>(
  session: Session | undefined,
  receivedAt: number,
  read: (remainingSeconds: number | null) => T,
): T {
  const deadline = session?.deadlineAt ? Date.parse(session.deadlineAt) : Number.NaN;
  const serverTime = session ? Date.parse(session.serverTime) : Number.NaN;
  const running = Number.isFinite(deadline) && Number.isFinite(serverTime);

  const offset = useMemo(
    () => (Number.isFinite(serverTime) ? serverTime - receivedAt : 0),
    [serverTime, receivedAt],
  );

  const subscribe = useCallback(
    (onTick: () => void) => {
      if (!running) {
        return () => undefined;
      }
      const timer = setInterval(onTick, 1000);
      return () => {
        clearInterval(timer);
      };
    },
    [running],
  );
  const snapshot = () =>
    read(
      running ? Math.max(0, Math.ceil((deadline - (nowInSeconds() * 1000 + offset)) / 1000)) : null,
    );
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

/**
 * The deadline belongs to the server. The page shows it by measuring how far the browser clock is from the server clock
 * when the session arrived (`serverTime`) and counting down from there, so a wrong local clock does not matter. Nothing the
 * client computes is ever sent back (API_DESIGN.md section 1, Time).
 *
 * Re-renders its component every second: use it where the seconds are shown (the timer), and {@link useTimeUp} elsewhere.
 */
export function useCountdown(session: Session | undefined, receivedAt: number): Countdown {
  const remainingSeconds = useServerClock(session, receivedAt, (remaining) => remaining);
  return { remainingSeconds, expired: remainingSeconds === 0 };
}

/** Whether the server's deadline has passed, by the same clock as {@link useCountdown}; re-renders once, when it does. */
export function useTimeUp(session: Session | undefined, receivedAt: number): boolean {
  return useServerClock(session, receivedAt, (remaining) => remaining === 0);
}
