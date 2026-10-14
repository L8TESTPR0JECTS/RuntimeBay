import { breatheLogo } from './loading-motion';

export interface LoadingOverlay { update(message: string): void; destroy(): void }

/** One body-level overlay covers the workspace, modal dialogs, and appearance drawer alike. */
export function mountLoadingOverlay(message: string): LoadingOverlay {
  const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const previousOverflow = document.body.style.overflow;
  const previousDialog = document.querySelector('[aria-modal="true"]');
  const siblings = Array.from(document.body.children).filter((node): node is HTMLElement => node instanceof HTMLElement);
  const previousInert = siblings.map(node => [node, node.inert] as const);
  const overlay = document.createElement('section');
  overlay.className = 'loading-overlay'; overlay.tabIndex = -1;
  overlay.setAttribute('role', 'dialog'); overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', 'Runtime Bay is loading');
  const content = document.createElement('div'); content.className = 'loading-overlay__content';
  const art = document.createElement('div'); art.className = 'loading-overlay__art'; art.setAttribute('aria-hidden', 'true');
  const halo = document.createElement('div'); halo.className = 'loading-overlay__halo';
  const logo = document.createElement('div'); logo.className = 'loading-overlay__logo';
  const caption = document.createElement('p'); caption.className = 'loading-overlay__message';
  caption.setAttribute('role', 'status'); caption.setAttribute('aria-live', 'polite'); caption.textContent = message;
  art.append(halo, logo); content.append(art, caption); overlay.append(content);
  for (const [node] of previousInert) node.inert = true;
  document.body.style.overflow = 'hidden'; document.body.append(overlay); overlay.focus({ preventScroll: true });
  const stopMotion = breatheLogo(logo, halo);
  const blockKeyboard = (event: KeyboardEvent) => {
    // macOS window close and quit remain available; app shortcuts and Tab stay blocked.
    if (event.metaKey && ['q', 'w'].includes(event.key.toLowerCase())) return;
    event.preventDefault(); event.stopImmediatePropagation(); overlay.focus({ preventScroll: true });
  };
  const retainFocus = (event: FocusEvent) => { if (event.target instanceof Node && !overlay.contains(event.target)) overlay.focus({ preventScroll: true }); };
  window.addEventListener('keydown', blockKeyboard, true);
  document.addEventListener('focusin', retainFocus, true);
  let destroyed = false;
  return {
    update(text) { caption.textContent = text; },
    destroy() {
      if (destroyed) return; destroyed = true;
      window.removeEventListener('keydown', blockKeyboard, true); document.removeEventListener('focusin', retainFocus, true);
      stopMotion(); overlay.remove();
      for (const [node, inert] of previousInert) if (node.isConnected) node.inert = inert;
      // An underlying dialog may have closed while the task was finishing.
      document.body.style.overflow = previousDialog && !previousDialog.isConnected && previousOverflow === 'hidden' ? '' : previousOverflow;
      // Angular may still be rendering the enabled button or closing its dialog.
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (document.querySelector('.loading-overlay')) return;
        const previous = previousFocus?.isConnected && !previousFocus.closest('[inert]') && !previousFocus.matches(':disabled')
          && previousFocus !== document.body ? previousFocus : null;
        const fallback = document.querySelector<HTMLElement>('.modal button:not(:disabled), .appearance-drawer button:not(:disabled), .sidebar button:not(:disabled)');
        (previous || fallback)?.focus({ preventScroll: true });
      }));
    }
  };
}
