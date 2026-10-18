function topKFrequent(nums, k) {
  const counts = new Map();
  for (const value of nums) counts.set(value, (counts.get(value) || 0) + 1);
  return [...counts.keys()]
    .sort((a, b) => counts.get(b) - counts.get(a) || a - b)
    .slice(0, k);
}
