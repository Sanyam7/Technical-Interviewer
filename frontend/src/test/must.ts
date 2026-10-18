/** A value a test knows is there; fails with a clear message instead of a non-null assertion. */
export function must<T>(value: T | null | undefined, what = 'value'): T {
  if (value === null || value === undefined) {
    throw new Error(`Expected ${what} to exist`);
  }
  return value;
}
