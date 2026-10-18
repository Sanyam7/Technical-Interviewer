export function reverseWords(s: string): string {
  const words: string[] = s.trim().split(/\s+/);
  return words.reverse().join(' ');
}
