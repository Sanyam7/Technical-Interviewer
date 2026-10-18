function isPalindrome(s: string): boolean {
  const t: string = s.toLowerCase().replace(/[^a-z0-9]/g, '');
  for (let i = 0, j = t.length - 1; i < j; i++, j--) {
    if (t[i] !== t[j]) return false;
  }
  return true;
}
