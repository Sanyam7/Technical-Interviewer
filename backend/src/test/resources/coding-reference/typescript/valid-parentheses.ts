const PAIRS: Record<string, string> = { '(': ')', '[': ']', '{': '}' };

function isValid(s: string): boolean {
  const stack: string[] = [];
  for (const c of s) {
    if (c in PAIRS) stack.push(PAIRS[c]);
    else if (stack.pop() !== c) return false;
  }
  return stack.length === 0;
}
