/** Show the current path component in full while omitting already typed parent directories. */
export function pathSuggestionLabel(token: string, suffix: string): string {
  const typedComponent = token.slice(token.lastIndexOf('/') + 1);
  return typedComponent + suffix;
}
