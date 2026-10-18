import { useId } from 'react';

import { cx } from '../../lib/cx';
import { formatScore } from '../../lib/format';

export interface TrendPoint {
  key: string;
  /** Text for the x axis and the tooltip, for example a date. */
  label: string;
  /** 0 to 100. */
  value: number;
}

interface TrendChartProps {
  points: TrendPoint[];
  /** What the numbers are, for the accessible name and the table. */
  label: string;
  className?: string;
}

const WIDTH = 640;
const HEIGHT = 240;
const PAD = { top: 16, right: 16, bottom: 32, left: 36 };
const GRID = [0, 25, 50, 75, 100];

/**
 * A line chart of scores over time (0 to 100 on a fixed axis, so a chart never exaggerates a small change). The same
 * numbers are available as a table for screen readers.
 */
export function TrendChart({ points, label, className }: TrendChartProps) {
  const gradientId = useId();
  const plotWidth = WIDTH - PAD.left - PAD.right;
  const plotHeight = HEIGHT - PAD.top - PAD.bottom;

  const x = (index: number) =>
    PAD.left + (points.length === 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth);
  const y = (value: number) => PAD.top + (1 - Math.min(100, Math.max(0, value)) / 100) * plotHeight;

  const coordinates = points.map((point, index) => `${x(index)},${y(point.value)}`);
  const line = coordinates.join(' ');
  const area =
    points.length > 1
      ? `${PAD.left},${PAD.top + plotHeight} ${line} ${x(points.length - 1)},${PAD.top + plotHeight}`
      : '';
  const first = points[0];
  const last = points[points.length - 1];

  return (
    <figure className={cx('w-full', className)}>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`${label}: ${points.length} values${first && last ? `, from ${formatScore(first.value)} to ${formatScore(last.value)}` : ''}`}
        className="h-auto w-full"
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--tone-chart-1)" stopOpacity="0.28" />
            <stop offset="100%" stopColor="var(--tone-chart-1)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {GRID.map((tick) => (
          <g key={tick}>
            <line
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={y(tick)}
              y2={y(tick)}
              stroke="var(--tone-chart-grid)"
              strokeDasharray={tick === 0 ? undefined : '3 4'}
            />
            <text
              x={PAD.left - 8}
              y={y(tick)}
              textAnchor="end"
              dominantBaseline="middle"
              className="fill-ink-muted text-[11px] tabular-nums"
            >
              {tick}
            </text>
          </g>
        ))}
        {area ? <polygon points={area} fill={`url(#${gradientId})`} /> : null}
        {points.length > 1 ? (
          <polyline
            points={line}
            fill="none"
            stroke="var(--tone-chart-1)"
            strokeWidth="2.5"
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ) : null}
        {points.map((point, index) => (
          <circle
            key={point.key}
            cx={x(index)}
            cy={y(point.value)}
            r="4.5"
            fill="var(--tone-surface)"
            stroke="var(--tone-chart-1)"
            strokeWidth="2.5"
          >
            <title>{`${point.label}: ${formatScore(point.value)}`}</title>
          </circle>
        ))}
        {first ? (
          <text
            x={x(0)}
            y={HEIGHT - 8}
            textAnchor={points.length > 1 ? 'start' : 'middle'}
            className="fill-ink-muted text-[11px]"
          >
            {first.label}
          </text>
        ) : null}
        {last && points.length > 1 ? (
          <text
            x={x(points.length - 1)}
            y={HEIGHT - 8}
            textAnchor="end"
            className="fill-ink-muted text-[11px]"
          >
            {last.label}
          </text>
        ) : null}
      </svg>
      <figcaption className="sr-only">
        <table>
          <caption>{label}</caption>
          <thead>
            <tr>
              <th scope="col">Interview</th>
              <th scope="col">Score</th>
            </tr>
          </thead>
          <tbody>
            {points.map((point) => (
              <tr key={point.key}>
                <th scope="row">{point.label}</th>
                <td>{formatScore(point.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </figcaption>
    </figure>
  );
}
