function isValid(s) {
  const closing = { '(': ')', '[': ']', '{': '}' };
  const stack = [];
  for (const c of s) {
    if (closing[c]) stack.push(closing[c]);
    else if (stack.pop() !== c) return false;
  }
  return stack.length === 0;
}
