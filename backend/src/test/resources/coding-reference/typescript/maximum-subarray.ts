function maxSubArray(nums: number[]): number {
  let best = nums[0];
  let current = nums[0];
  for (const value of nums.slice(1)) {
    current = Math.max(value, current + value);
    best = Math.max(best, current);
  }
  return best;
}
