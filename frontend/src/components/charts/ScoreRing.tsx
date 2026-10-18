import { cx } from '../../lib/cx';
import { formatScore } from '../../lib/format';
import { BAND_FILL, scoreBand } from '../../lib/scoring';

interface ScoreRingProps {
  /** 0 to 100. */
  score: number;
  /** What the score is a score of, for the accessible name. */
  label: string;
  /** Diameter in px. */
  size?: number;
  className?: string;
}

const STROKE = 10;

/** A circular gauge with the number in the middle. The number is real text, so it is also what assistive technology reads. */
export function ScoreRing({ score, label, size = 168, className }: ScoreRingProps) {
  const clamped = Math.min(100, Math.max(0, score));
  const radius = (100 - STROKE) / 2;
  const circumference = 2 * Math.PI * radius;

  return (
    <div
      role="img"
      aria-label={`${label}: ${formatScore(score)} out of 100`}
      className={cx('relative inline-grid place-items-center', className)}
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 100 100" className="absolute inset-0 -rotate-90" aria-hidden="true">
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          stroke="var(--tone-chart-grid)"
          strokeWidth={STROKE}
        />
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          stroke={BAND_FILL[scoreBand(score)]}
          strokeWidth={STROKE}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - clamped / 100)}
          className="transition-[stroke-dashoffset] duration-700 motion-reduce:transition-none"
        />
      </svg>
      <div className="relative text-center" aria-hidden="true">
        <div className="font-bold tracking-tighter tabular-nums" style={{ fontSize: size * 0.28 }}>
          {formatScore(score)}
        </div>
        <div className="text-ink-muted -mt-0.5 text-xs font-medium">out of 100</div>
      </div>
    </div>
  );
}
