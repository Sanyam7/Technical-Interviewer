import { useId } from 'react';

import type { Architecture } from '../../api/types';
import { cx } from '../../lib/cx';
import { describe, KINDS, kindLabel, layout, NODE_HEIGHT, NODE_WIDTH } from './architecture';

/** Each band of the drawing has its own accent, so clients, services, queues and data read apart at a glance. */
const BAND_COLOR = [
  'var(--tone-chart-2)',
  'var(--tone-chart-4)',
  'var(--tone-chart-1)',
  'var(--tone-chart-5)',
  'var(--tone-chart-3)',
];
const BAND = new Map(KINDS.map((entry) => [entry.kind, entry.band]));
const NAME_CHARACTERS = 21;

function fit(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

/**
 * Draws an architecture diagram: boxes in bands from clients at the top to data at the bottom, arrows for connections (dashed
 * when asynchronous) with their labels. The drawing is an image with a text alternative that says the same in words.
 */
export function ArchitectureDiagramView({
  diagram,
  className,
}: {
  diagram: Architecture;
  className?: string;
}) {
  const markerId = useId().replace(/:/g, '');
  if (diagram.components.length === 0) {
    return null;
  }
  const { width, height, nodes, edges } = layout(diagram);
  return (
    <div
      className={cx('border-line bg-surface-muted overflow-x-auto rounded-xl border', className)}
    >
      <svg
        role="img"
        aria-label={describe(diagram)}
        viewBox={`0 0 ${width} ${height}`}
        className="mx-auto block h-auto w-full min-w-[22rem]"
        style={{ maxWidth: `${width}px` }}
      >
        <defs>
          <marker
            id={markerId}
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--tone-ink-muted)" />
          </marker>
        </defs>
        {edges.map(({ connection, path }) => (
          <path
            key={`${connection.from}>${connection.to}`}
            d={path}
            fill="none"
            stroke="var(--tone-ink-muted)"
            strokeWidth={1.5}
            strokeDasharray={connection.async ? '6 4' : undefined}
            markerEnd={`url(#${markerId})`}
          >
            {connection.label ? <title>{connection.label}</title> : null}
          </path>
        ))}
        {nodes.map(({ component, x, y }) => {
          const color = BAND_COLOR[BAND.get(component.kind) ?? 2];
          return (
            <g key={component.id}>
              <title>{`${component.name} (${kindLabel(component.kind)})${component.notes ? `: ${component.notes}` : ''}`}</title>
              <rect
                x={x}
                y={y}
                width={NODE_WIDTH}
                height={NODE_HEIGHT}
                rx={10}
                fill="var(--tone-surface)"
                stroke={color}
                strokeWidth={2}
              />
              <rect x={x} y={y} width={6} height={NODE_HEIGHT} rx={3} fill={color} />
              <text x={x + 16} y={y + 23} fontSize={13} fontWeight={600} fill="var(--tone-ink)">
                {fit(component.name, NAME_CHARACTERS)}
              </text>
              <text x={x + 16} y={y + 41} fontSize={11} fill="var(--tone-ink-muted)">
                {kindLabel(component.kind)}
              </text>
            </g>
          );
        })}
        {edges.map(({ connection, label }) =>
          label ? (
            <g key={`label-${connection.from}>${connection.to}`}>
              <rect
                x={label.x - label.width / 2}
                y={label.y - 9}
                width={label.width}
                height={17}
                rx={4}
                fill="var(--tone-page)"
                stroke="var(--tone-line)"
              />
              <text
                x={label.x}
                y={label.y + 3}
                fontSize={10.5}
                textAnchor="middle"
                fill="var(--tone-ink)"
              >
                {label.text}
              </text>
            </g>
          ) : null,
        )}
      </svg>
    </div>
  );
}
