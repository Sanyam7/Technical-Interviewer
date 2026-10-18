const DATE = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const DATE_TIME = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const SHORT_DATE = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });

function parse(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDate(iso: string | null | undefined): string {
  const date = parse(iso);
  return date ? DATE.format(date) : '—';
}

export function formatShortDate(iso: string | null | undefined): string {
  const date = parse(iso);
  return date ? SHORT_DATE.format(date) : '—';
}

export function formatDateTime(iso: string | null | undefined): string {
  const date = parse(iso);
  return date ? DATE_TIME.format(date) : '—';
}

/** 754 → "12:34"; 3 725 → "1:02:05". Negative values show as zero. */
export function formatClock(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = String(seconds % 60).padStart(2, '0');
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}`
    : `${String(minutes).padStart(2, '0')}:${rest}`;
}

/** A score from the backend (0 to 100, up to two decimals) as shown to people: no trailing zeros. */
export function formatScore(score: number): string {
  return Number.isInteger(score) ? String(score) : score.toFixed(1);
}

export function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`;
}
