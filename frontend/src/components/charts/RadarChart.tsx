import { cx } from '../../lib/cx';
import { formatScore } from '../../lib/format';

export interface RadarAxis {
  key: string;
  label: string;
  /** 0 to 100. */
  value: number;
}

interface RadarChartProps {
  /** At least three axes; with fewer, a radar has no shape and the caller should show bars. */
  axes: RadarAxis[];
  label: string;
  className?: string;
}

const WIDTH = 460;
const HEIGHT = 320;
const CENTER_X = WIDTH / 2;
const CENTER_Y = HEIGHT / 2;
const RADIUS = 104;
const RINGS = [25, 50, 75, 100];

function point(index: number, count: number, share: number): [number, number] {
  const angle = (index / count) * 2 * Math.PI - Math.PI / 2;
  return [CENTER_X + Math.cos(angle) * RADIUS * share, CENTER_Y + Math.sin(angle) * RADIUS * share];
}

function anchor(index: number, count: number): 'start' | 'middle' | 'end' {
  const cos = Math.cos((index / count) * 2 * Math.PI - Math.PI / 2);
  if (Math.abs(cos) < 0.2) {
    return 'middle';
  }
  return cos > 0 ? 'start' : 'end';
}

/** Shows the balance of skill scores at a glance; the numbers are written next to each axis. */
export function RadarChart({ axes, label, className }: RadarChartProps) {
  const count = axes.length;
  const shape = axes
    .map((axis, index) =>
      point(index, count, Math.min(100, Math.max(0, axis.value)) / 100).join(','),
    )
    .join(' ');

  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={`${label}: ${axes.map((axis) => `${axis.label} ${formatScore(axis.value)}`).join(', ')}`}
      className={cx('h-auto w-full max-w-md', className)}
    >
      {RINGS.map((ring) => (
        <polygon
          key={ring}
          points={axes.map((_, index) => point(index, count, ring / 100).join(',')).join(' ')}
          fill="none"
          stroke="var(--tone-chart-grid)"
        />
      ))}
      {axes.map((axis, index) => {
        const [x, y] = point(index, count, 1);
        return (
          <line
            key={axis.key}
            x1={CENTER_X}
            y1={CENTER_Y}
            x2={x}
            y2={y}
            stroke="var(--tone-chart-grid)"
          />
        );
      })}
      <polygon
        points={shape}
        fill="var(--tone-chart-1)"
        fillOpacity="0.2"
        stroke="var(--tone-chart-1)"
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
      {axes.map((axis, index) => {
        const [px, py] = point(index, count, Math.min(100, Math.max(0, axis.value)) / 100);
        const [lx, ly] = point(index, count, 1.2);
        return (
          <g key={axis.key}>
            <circle cx={px} cy={py} r="4" fill="var(--tone-chart-1)" />
            <text
              x={lx}
              y={ly}
              textAnchor={anchor(index, count)}
              dominantBaseline="middle"
              className="fill-ink text-[13px] font-medium"
            >
              {axis.label}
              <tspan x={lx} dy="15" className="fill-ink-muted font-normal tabular-nums">
                {formatScore(axis.value)}
              </tspan>
            </text>
          </g>
        );
      })}
    </svg>
  );
}
