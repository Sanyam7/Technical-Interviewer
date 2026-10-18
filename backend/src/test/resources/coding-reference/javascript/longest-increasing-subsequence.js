function lengthOfLIS(nums) {
  const tails = [];
  for (const value of nums) {
    let low = 0;
    let high = tails.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if (tails[middle] < value) low = middle + 1;
      else high = middle;
    }
    tails[low] = value;
  }
  return tails.length;
}
