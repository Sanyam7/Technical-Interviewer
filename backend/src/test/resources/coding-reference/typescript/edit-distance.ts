function minDistance(word1: string, word2: string): number {
  let previous: number[] = Array.from({ length: word2.length + 1 }, (_, j) => j);
  for (let i = 1; i <= word1.length; i++) {
    const current: number[] = [i];
    for (let j = 1; j <= word2.length; j++) {
      current[j] =
        word1[i - 1] === word2[j - 1]
          ? previous[j - 1]
          : 1 + Math.min(previous[j - 1], previous[j], current[j - 1]);
    }
    previous = current;
  }
  return previous[word2.length];
}
