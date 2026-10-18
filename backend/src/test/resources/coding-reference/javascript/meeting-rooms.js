function minMeetingRooms(intervals) {
  const starts = intervals.map((m) => m[0]).sort((a, b) => a - b);
  const ends = intervals.map((m) => m[1]).sort((a, b) => a - b);
  let rooms = 0;
  let endIndex = 0;
  for (const start of starts) {
    if (start >= ends[endIndex]) endIndex++;
    else rooms++;
  }
  return rooms;
}
