/** Joins class names, skipping the falsy ones: `cx('a', active && 'b')`. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}
