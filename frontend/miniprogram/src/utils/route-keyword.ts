/** Route query values are URI encoded; decode once before displaying or searching. */
export function routeKeyword(value?: string): string {
  if (!value) return '';
  try {
    return decodeURIComponent(value);
  } catch {
    // Keep literal percent signs and malformed external links usable.
    return value;
  }
}
