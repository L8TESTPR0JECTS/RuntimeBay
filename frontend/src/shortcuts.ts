export type WorkspaceCommand = 'new-terminal' | 'import-workflow' | 'previous-terminal' | 'next-terminal';

/** App commands use Command only; Control belongs to the shell and Option to text entry. */
export function workspaceShortcut(event: Pick<KeyboardEvent, 'key' | 'code' | 'metaKey' | 'ctrlKey' | 'altKey' | 'shiftKey' | 'repeat' | 'defaultPrevented' | 'isComposing'>): WorkspaceCommand | null {
  if (!event.metaKey || event.ctrlKey || event.altKey || event.repeat || event.defaultPrevented || event.isComposing) return null;
  if (event.shiftKey) {
    if (event.code === 'BracketLeft') return 'previous-terminal';
    if (event.code === 'BracketRight') return 'next-terminal';
    return null;
  }
  switch (event.key.toLowerCase()) {
    case 't': return 'new-terminal';
    case 'o': case 'k': return 'import-workflow';
    default: return null;
  }
}

export function adjacentTerminal(ids: string[], selected: string | null, direction: number): string | null {
  if (!ids.length) return null;
  const index = ids.indexOf(selected || '');
  if (index < 0) return direction < 0 ? ids.at(-1)! : ids[0];
  return ids[(index + direction + ids.length) % ids.length];
}
