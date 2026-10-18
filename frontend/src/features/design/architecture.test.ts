import { describe, expect, it } from 'vitest';

import {
  addComponent,
  addConnection,
  componentProblem,
  connectionProblem,
  describe as describeDiagram,
  EMPTY,
  forAnswer,
  layout,
  MAX_COMPONENTS,
  newId,
  NODE_WIDTH,
  NODE_HEIGHT,
  removeComponent,
  STARTER,
} from './architecture';

describe('the architecture diagram model', () => {
  it('adds components with unique ids and checks names the way the server does', () => {
    let diagram = addComponent(EMPTY, 'SERVICE', '  Order service ', 'validates orders');
    diagram = addComponent(diagram, 'DATABASE', 'Order service!', '');

    expect(diagram.components.map((c) => c.id)).toEqual(['order-service', 'order-service-2']);
    expect(diagram.components[0]).toEqual({
      id: 'order-service',
      kind: 'SERVICE',
      name: 'Order service',
      notes: 'validates orders',
    });
    expect(diagram.components[1]).not.toHaveProperty('notes');
    expect(componentProblem(diagram, 'ORDER SERVICE')).toMatch(/already has this name/);
    expect(componentProblem(diagram, ' ')).toMatch(/name/);
    expect(componentProblem(diagram, 'x'.repeat(41))).toMatch(/40 characters/);
    expect(newId(EMPTY, '!!!')).toBe('node');
    const full = {
      ...EMPTY,
      components: Array.from({ length: MAX_COMPONENTS }, (_, i) => ({
        id: `c${i}`,
        kind: 'SERVICE' as const,
        name: `S${i}`,
      })),
    };
    expect(componentProblem(full, 'One more')).toMatch(/at most 24/);
  });

  it('connects two different components once per direction and removes a component with its connections', () => {
    const diagram = addConnection(STARTER, 'db', 'api', ' replication lag ', true);

    expect(connectionProblem(diagram, 'api', 'api')).toMatch(/two different/);
    expect(connectionProblem(diagram, 'api', 'db')).toMatch(/already connected/);
    expect(connectionProblem(diagram, '', 'db')).toMatch(/both ends/);
    expect(diagram.connections.at(-1)).toEqual({
      from: 'db',
      to: 'api',
      label: 'replication lag',
      async: true,
    });
    const without = removeComponent(diagram, 'db');
    expect(without.components.map((c) => c.id)).not.toContain('db');
    expect(without.connections.every((c) => c.from !== 'db' && c.to !== 'db')).toBe(true);
    expect(forAnswer(EMPTY)).toBeUndefined();
    expect(forAnswer(STARTER)).toBe(STARTER);
  });

  it('lays the components out top to bottom from clients to data and routes every connection', () => {
    const { nodes, edges, width, height } = layout(STARTER);
    const y = (id: string) => nodes.find((node) => node.component.id === id)?.y ?? -1;

    expect(y('clients')).toBeLessThan(y('lb'));
    expect(y('lb')).toBeLessThan(y('api'));
    expect(y('api')).toBeLessThan(y('db'));
    expect(edges).toHaveLength(3);
    expect(edges[0]?.path).toMatch(/^M [\d.]+ [\d.]+ C /);
    expect(height).toBeGreaterThan(4 * NODE_HEIGHT);
    expect(width).toBeGreaterThan(0);
    expect(describeDiagram(STARTER)).toContain('Load balancer to API service');
  });

  it('puts at most four components in a row', () => {
    let diagram = EMPTY;
    for (let i = 0; i < 6; i++) {
      diagram = addComponent(diagram, 'SERVICE', `Service ${i}`, '');
    }
    const rows = new Set(layout(diagram).nodes.map((node) => node.y));

    expect(rows.size).toBe(2);
  });

  it('never draws a connection through a box it does not connect, across rows or within one', () => {
    let diagram = STARTER;
    diagram = addComponent(diagram, 'QUEUE', 'Kafka events', '');
    diagram = addComponent(diagram, 'WORKER', 'Workers', '');
    diagram = addComponent(diagram, 'CACHE', 'Redis cache', '');
    diagram = addComponent(diagram, 'SERVICE', 'Search service', '');
    diagram = addComponent(diagram, 'SERVICE', 'Billing service', '');
    diagram = addConnection(diagram, 'api', 'kafka-events', 'publishes', true);
    diagram = addConnection(diagram, 'kafka-events', 'workers', 'consumes', true);
    diagram = addConnection(diagram, 'api', 'redis-cache', 'read-through', false);
    diagram = addConnection(diagram, 'clients', 'redis-cache', '', false);
    diagram = addConnection(diagram, 'api', 'billing-service', '', false);
    const { nodes, edges } = layout(diagram);
    // API, search and billing share a row in that order: API → billing has the search service in between.
    expect(nodes.filter((n) => n.component.kind === 'SERVICE')).toHaveLength(3);

    /** Points along an SVG path made of M, C and L commands. */
    const sample = (path: string): [number, number][] => {
      const numbers = (path.match(/-?[\d.]+/g) ?? []).map(Number);
      const next = () => numbers.shift() ?? Number.NaN;
      const points: [number, number][] = [];
      let current: [number, number] = [0, 0];
      for (const command of path.match(/[MCL]/g) ?? []) {
        const controls =
          command === 'C'
            ? [next(), next(), next(), next()]
            : [Number.NaN, Number.NaN, Number.NaN, Number.NaN];
        const [c1x = 0, c1y = 0, c2x = 0, c2y = 0] = controls;
        const end: [number, number] = [next(), next()];
        if (command !== 'M') {
          for (let t = 0; t <= 1; t += 0.02) {
            const u = 1 - t;
            points.push(
              command === 'L'
                ? [current[0] + (end[0] - current[0]) * t, current[1] + (end[1] - current[1]) * t]
                : [
                    u * u * u * current[0] +
                      3 * u * u * t * c1x +
                      3 * u * t * t * c2x +
                      t * t * t * end[0],
                    u * u * u * current[1] +
                      3 * u * u * t * c1y +
                      3 * u * t * t * c2y +
                      t * t * t * end[1],
                  ],
            );
          }
        }
        current = end;
      }
      return points;
    };

    for (const edge of edges) {
      const others = nodes.filter(
        (n) => n.component.id !== edge.connection.from && n.component.id !== edge.connection.to,
      );
      for (const [x, y] of sample(edge.path)) {
        const inside = others.find(
          (n) => x > n.x && x < n.x + NODE_WIDTH && y > n.y && y < n.y + NODE_HEIGHT,
        );
        expect(
          inside?.component.id,
          `${edge.connection.from} → ${edge.connection.to} at ${x},${y}`,
        ).toBeUndefined();
      }
    }
  });

  it('never draws a label over a box or another label, and keeps the ones it cannot place in the title', () => {
    let diagram = STARTER;
    diagram = addComponent(diagram, 'QUEUE', 'Kafka events', '');
    diagram = addComponent(diagram, 'WORKER', 'Notification workers', '');
    diagram = addComponent(diagram, 'CACHE', 'Redis cache', '');
    diagram = addConnection(diagram, 'api', 'kafka-events', 'publishes ItemCreated', true);
    diagram = addConnection(diagram, 'kafka-events', 'notification-workers', 'consumes', true);
    diagram = addConnection(diagram, 'api', 'redis-cache', 'read-through by id', false);
    const { nodes, edges } = layout(diagram);
    const boxes = [
      ...nodes.map((n) => ({ x: n.x, y: n.y, width: NODE_WIDTH, height: NODE_HEIGHT })),
    ];
    const labels = edges.flatMap((edge) => (edge.label ? [edge.label] : []));
    const overlap = (a: { x: number; y: number; width: number; height: number }, b: typeof a) =>
      a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

    expect(labels.length).toBeGreaterThan(0);
    labels.forEach((label, i) => {
      const box = {
        x: label.x - label.width / 2,
        y: label.y - 8.5,
        width: label.width,
        height: 17,
      };
      expect(boxes.some((other) => overlap(box, other))).toBe(false);
      labels.slice(i + 1).forEach((next) => {
        expect(
          overlap(box, {
            x: next.x - next.width / 2,
            y: next.y - 8.5,
            width: next.width,
            height: 17,
          }),
        ).toBe(false);
      });
    });
  });
});
