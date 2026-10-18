import type {
  Architecture,
  ArchitectureComponent,
  ArchitectureConnection,
  ComponentKind,
} from '../../api/types';

/** The server's limits (ArchitectureDiagram on the backend); the editor stops at them so a diagram is never refused. */
export const MAX_COMPONENTS = 24;
export const MAX_CONNECTIONS = 48;
export const MAX_NAME = 40;
export const MAX_NOTES = 160;
export const MAX_LABEL = 60;

/** Every kind, in the order the editor offers them, with how it is called and the band of the drawing it sits in. */
export const KINDS: readonly { kind: ComponentKind; label: string; band: number }[] = [
  { kind: 'CLIENT', label: 'Client', band: 0 },
  { kind: 'CDN', label: 'CDN', band: 1 },
  { kind: 'LOAD_BALANCER', label: 'Load balancer', band: 1 },
  { kind: 'API_GATEWAY', label: 'API gateway', band: 1 },
  { kind: 'SERVICE', label: 'Service', band: 2 },
  { kind: 'EXTERNAL', label: 'External system', band: 2 },
  { kind: 'QUEUE', label: 'Queue or stream', band: 3 },
  { kind: 'WORKER', label: 'Worker', band: 3 },
  { kind: 'CACHE', label: 'Cache', band: 4 },
  { kind: 'DATABASE', label: 'Database', band: 4 },
  { kind: 'OBJECT_STORAGE', label: 'Object storage', band: 4 },
  { kind: 'SEARCH', label: 'Search index', band: 4 },
];

const BY_KIND = new Map(KINDS.map((entry) => [entry.kind, entry]));

export function kindLabel(kind: ComponentKind): string {
  return BY_KIND.get(kind)?.label ?? kind;
}

export const EMPTY: Architecture = { components: [], connections: [] };

/** A diagram most designs start from: users reach stateless services behind a load balancer that keep data in a database. */
export const STARTER: Architecture = {
  components: [
    { id: 'clients', kind: 'CLIENT', name: 'Web and mobile clients' },
    { id: 'lb', kind: 'LOAD_BALANCER', name: 'Load balancer' },
    { id: 'api', kind: 'SERVICE', name: 'API service', notes: 'stateless' },
    { id: 'db', kind: 'DATABASE', name: 'Primary database' },
  ],
  connections: [
    { from: 'clients', to: 'lb', label: 'HTTPS', async: false },
    { from: 'lb', to: 'api', async: false },
    { from: 'api', to: 'db', label: 'reads and writes', async: false },
  ],
};

/** Why a component cannot be added as it is, or null when it can. */
export function componentProblem(diagram: Architecture, name: string): string | null {
  const trimmed = name.trim();
  if (trimmed === '') {
    return 'Give the component a name.';
  }
  if (trimmed.length > MAX_NAME) {
    return `Keep the name to ${MAX_NAME} characters.`;
  }
  if (diagram.components.some((c) => c.name.toLowerCase() === trimmed.toLowerCase())) {
    return 'Another component already has this name.';
  }
  if (diagram.components.length >= MAX_COMPONENTS) {
    return `A diagram has at most ${MAX_COMPONENTS} components.`;
  }
  return null;
}

/** Why a connection cannot be added, or null when it can. */
export function connectionProblem(diagram: Architecture, from: string, to: string): string | null {
  if (from === '' || to === '') {
    return 'Choose both ends of the connection.';
  }
  if (from === to) {
    return 'A connection joins two different components.';
  }
  if (diagram.connections.some((c) => c.from === from && c.to === to)) {
    return 'These two components are already connected in this direction.';
  }
  if (diagram.connections.length >= MAX_CONNECTIONS) {
    return `A diagram has at most ${MAX_CONNECTIONS} connections.`;
  }
  return null;
}

/** An id for a new component: its name in lower-case letters and digits, made unique, at most 20 characters. */
export function newId(diagram: Architecture, name: string): string {
  const base =
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 16) || 'node';
  const taken = new Set(diagram.components.map((c) => c.id));
  let id = base;
  for (let n = 2; taken.has(id); n++) {
    id = `${base}-${n}`;
  }
  return id;
}

export function addComponent(
  diagram: Architecture,
  kind: ComponentKind,
  name: string,
  notes: string,
): Architecture {
  const component: ArchitectureComponent = {
    id: newId(diagram, name),
    kind,
    name: name.trim(),
    ...(notes.trim() ? { notes: notes.trim().slice(0, MAX_NOTES) } : {}),
  };
  return { ...diagram, components: [...diagram.components, component] };
}

/** Removes a component and every connection that touches it. */
export function removeComponent(diagram: Architecture, id: string): Architecture {
  return {
    components: diagram.components.filter((c) => c.id !== id),
    connections: diagram.connections.filter((c) => c.from !== id && c.to !== id),
  };
}

export function addConnection(
  diagram: Architecture,
  from: string,
  to: string,
  label: string,
  async: boolean,
): Architecture {
  const connection: ArchitectureConnection = {
    from,
    to,
    async,
    ...(label.trim() ? { label: label.trim().slice(0, MAX_LABEL) } : {}),
  };
  return { ...diagram, connections: [...diagram.connections, connection] };
}

export function removeConnection(diagram: Architecture, index: number): Architecture {
  return { ...diagram, connections: diagram.connections.filter((_, i) => i !== index) };
}

/** The diagram to send with an answer: none when nothing was drawn. */
export function forAnswer(diagram: Architecture): Architecture | undefined {
  return diagram.components.length > 0 ? diagram : undefined;
}

// ---- layout ----------------------------------------------------------------------------------------------------

export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 54;
const GAP_X = 28;
const GAP_Y = 64;
const PADDING = 24;
const PER_ROW = 4;

export interface PlacedNode {
  component: ArchitectureComponent;
  x: number;
  y: number;
}

/** Where a connection's label is drawn; absent when no free spot along the curve was found (the label is then in its title). */
export interface PlacedLabel {
  text: string;
  x: number;
  y: number;
  width: number;
}

export interface PlacedEdge {
  connection: ArchitectureConnection;
  path: string;
  label: PlacedLabel | null;
}

export interface Layout {
  width: number;
  height: number;
  nodes: PlacedNode[];
  edges: PlacedEdge[];
}

/**
 * Places the components top to bottom by what they are (clients, edge, services, asynchronous work, data), at most four to a
 * row and each row centred, then draws every connection as a curve from the bottom of the upper box to the top of the lower
 * one (or between the sides of two boxes in one row). A connection never passes behind a box it does not connect: one that
 * skips a row goes through the nearest gap of that row, and one between two boxes of a row with boxes in between goes around
 * them. No dragging: the drawing follows the diagram, so it is the same for the candidate, the report and a screen reader's
 * list.
 */
export function layout(diagram: Architecture): Layout {
  const rows: ArchitectureComponent[][] = [];
  for (let band = 0; band <= 4; band++) {
    const members = diagram.components.filter((c) => (BY_KIND.get(c.kind)?.band ?? 2) === band);
    for (let i = 0; i < members.length; i += PER_ROW) {
      rows.push(members.slice(i, i + PER_ROW));
    }
  }
  const widest = Math.max(1, ...rows.map((row) => row.length));
  const width = PADDING * 2 + widest * NODE_WIDTH + (widest - 1) * GAP_X;
  const height =
    PADDING * 2 + Math.max(1, rows.length) * NODE_HEIGHT + Math.max(0, rows.length - 1) * GAP_Y;
  const nodes: PlacedNode[] = [];
  rows.forEach((row, r) => {
    const rowWidth = row.length * NODE_WIDTH + (row.length - 1) * GAP_X;
    const start = (width - rowWidth) / 2;
    row.forEach((component, i) => {
      nodes.push({
        component,
        x: start + i * (NODE_WIDTH + GAP_X),
        y: PADDING + r * (NODE_HEIGHT + GAP_Y),
      });
    });
  });
  const at = new Map(nodes.map((node) => [node.component.id, node]));
  const rowTops = [...new Set(nodes.map((node) => node.y))].sort((a, b) => a - b);
  const rowXs = new Map(
    rowTops.map((y) => [
      y,
      nodes
        .filter((node) => node.y === y)
        .map((node) => node.x)
        .sort((a, b) => a - b),
    ]),
  );
  const taken: Box[] = nodes.map((node) => ({
    x: node.x,
    y: node.y,
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
  }));
  const edges: PlacedEdge[] = [];
  for (const connection of diagram.connections) {
    const from = at.get(connection.from);
    const to = at.get(connection.to);
    if (!from || !to) {
      continue;
    }
    const curve = route(from, to, rowTops, rowXs);
    edges.push({
      connection,
      path: curve.path,
      label: placeLabel(connection.label ?? undefined, curve.point, taken),
    });
  }
  return { width, height, nodes, edges };
}

interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

const LABEL_HEIGHT = 17;
const LABEL_CHARACTERS = 26;

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** The first spot along the curve where the label covers no box and no other label; null when there is none. */
function placeLabel(
  text: string | undefined,
  point: (t: number) => [number, number],
  taken: Box[],
): PlacedLabel | null {
  if (!text) {
    return null;
  }
  const shown = text.length <= LABEL_CHARACTERS ? text : `${text.slice(0, LABEL_CHARACTERS - 1)}…`;
  const width = shown.length * 6.2 + 10;
  for (const t of [0.5, 0.35, 0.65, 0.2, 0.8]) {
    const [x, y] = point(t);
    const box = { x: x - width / 2, y: y - LABEL_HEIGHT / 2, width, height: LABEL_HEIGHT };
    if (!taken.some((other) => overlaps(box, other))) {
      taken.push(box);
      return { text: shown, x, y, width };
    }
  }
  return null;
}

type Point = [number, number];

/** Cubic curves through the points, vertical at each of them; the label point walks the segments evenly. */
function through(points: Point[]): { path: string; point: (t: number) => Point } {
  const segments = points.slice(1).map((end, i) => {
    const start = points[i] ?? end;
    const bend = (end[1] - start[1]) / 2;
    return [start, [start[0], start[1] + bend], [end[0], end[1] - bend], end] as const;
  });
  const path = segments
    .map(
      ([start, c1, c2, end], i) =>
        `${i === 0 ? `M ${start[0]} ${start[1]} ` : ''}C ${c1[0]} ${c1[1]}, ${c2[0]} ${c2[1]}, ${end[0]} ${end[1]}`,
    )
    .join(' ');
  const point = (t: number): Point => {
    const scaled = Math.min(t * segments.length, segments.length - 1e-9);
    const segment = segments[Math.floor(scaled)];
    if (!segment) {
      return points[0] ?? [0, 0];
    }
    const [p0, p1, p2, p3] = segment;
    const s = scaled - Math.floor(scaled);
    const u = 1 - s;
    const a = u * u * u;
    const b = 3 * u * u * s;
    const c = 3 * u * s * s;
    const d = s * s * s;
    return [
      a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0],
      a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1],
    ];
  };
  return { path, point };
}

/** The x of a free vertical passage through a row (a gap between two boxes, or beside the row) nearest to {@code wanted}. */
function passage(xs: number[], wanted: number): number {
  const free = [(xs[0] ?? 0) - GAP_X / 2, ...xs.map((x) => x + NODE_WIDTH + GAP_X / 2)];
  const inBox = xs.some((x) => wanted > x - GAP_X / 4 && wanted < x + NODE_WIDTH + GAP_X / 4);
  if (!inBox) {
    return wanted;
  }
  return free.reduce((best, x) => (Math.abs(x - wanted) < Math.abs(best - wanted) ? x : best));
}

function route(
  from: PlacedNode,
  to: PlacedNode,
  rowTops: number[],
  rowXs: Map<number, number[]>,
): { path: string; point: (t: number) => Point } {
  if (from.y === to.y) {
    const [left, right] = from.x < to.x ? [from, to] : [to, from];
    const between = (rowXs.get(from.y) ?? []).some((x) => x > left.x && x < right.x);
    if (between) {
      // Around the boxes in between: a shallow U under the row (over it for the last row), inside the padding.
      const below = from.y !== rowTops.at(-1) || from.y === rowTops[0];
      const y = below ? from.y + NODE_HEIGHT : from.y;
      const depth = below ? PADDING - 4 : -(PADDING - 4);
      const x1 = from.x + NODE_WIDTH / 2;
      const x2 = to.x + NODE_WIDTH / 2;
      return {
        path: `M ${x1} ${y} C ${x1} ${y + depth * 1.3}, ${x2} ${y + depth * 1.3}, ${x2} ${y}`,
        point: (t) => [x1 + (x2 - x1) * t, y + depth * 0.75],
      };
    }
    const leftToRight = from.x < to.x;
    const x1 = leftToRight ? from.x + NODE_WIDTH : from.x;
    const x2 = leftToRight ? to.x : to.x + NODE_WIDTH;
    const y = from.y + NODE_HEIGHT / 2;
    return { path: `M ${x1} ${y} L ${x2} ${y}`, point: (t) => [x1 + (x2 - x1) * t, y - 10] };
  }
  const down = from.y < to.y;
  const start: Point = [from.x + NODE_WIDTH / 2, down ? from.y + NODE_HEIGHT : from.y];
  const end: Point = [to.x + NODE_WIDTH / 2, down ? to.y : to.y + NODE_HEIGHT];
  const skipped = rowTops
    .filter((y) => y > Math.min(from.y, to.y) && y < Math.max(from.y, to.y))
    .sort((a, b) => (down ? a - b : b - a));
  const points: Point[] = [start];
  for (const top of skipped) {
    // Where the straight line would cross this row, moved to the nearest gap when a box is there.
    const middle = top + NODE_HEIGHT / 2;
    const share = (middle - start[1]) / (end[1] - start[1]);
    const x = passage(rowXs.get(top) ?? [], start[0] + (end[0] - start[0]) * share);
    const [entry, exit] = down ? [top, top + NODE_HEIGHT] : [top + NODE_HEIGHT, top];
    points.push([x, entry], [x, exit]);
  }
  points.push(end);
  return through(points);
}

/** The diagram in words, for screen readers and the report. */
export function describe(diagram: Architecture): string {
  const names = new Map(diagram.components.map((c) => [c.id, c.name]));
  const parts = diagram.connections.map(
    (c) =>
      `${names.get(c.from) ?? c.from} to ${names.get(c.to) ?? c.to}${c.async ? ' (asynchronous)' : ''}${c.label ? `: ${c.label}` : ''}`,
  );
  return `Architecture diagram with ${diagram.components.length} components (${diagram.components
    .map((c) => `${c.name}, ${kindLabel(c.kind).toLowerCase()}`)
    .join(
      '; ',
    )})${parts.length > 0 ? ` and ${parts.length} connections: ${parts.join('; ')}` : ''}.`;
}
