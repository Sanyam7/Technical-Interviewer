function twoSum(nums: number[], target: number): number[] {
  const seen = new Map<number, number>();
  for (let i = 0; i < nums.length; i++) {
    const j: number | undefined = seen.get(target - nums[i]);
    if (j !== undefined) return [j, i];
    if (!seen.has(nums[i])) seen.set(nums[i], i);
  }
  return [];
}
