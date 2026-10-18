enum Edge {
  Start,
  End,
}

function minMeetingRooms(intervals: number[][]): number {
  const events: Array<[number, Edge]> = [];
  for (const [start, end] of intervals) {
    events.push([start, Edge.Start], [end, Edge.End]);
  }
  // An end at time t frees its room before a start at t takes one.
  events.sort((a, b) => a[0] - b[0] || (a[1] === Edge.End ? -1 : 1) - (b[1] === Edge.End ? -1 : 1));
  let rooms = 0;
  let best = 0;
  for (const [, edge] of events) {
    rooms += edge === Edge.Start ? 1 : -1;
    best = Math.max(best, rooms);
  }
  return best;
}
