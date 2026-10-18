function lengthOfLongestSubstring(s: string): number {
  const last = new Map<string, number>();
  let start = 0;
  let best = 0;
  for (let i = 0; i < s.length; i++) {
    const previous = last.get(s[i]);
    if (previous !== undefined && previous >= start) start = previous + 1;
    last.set(s[i], i);
    best = Math.max(best, i - start + 1);
  }
  return best;
}
