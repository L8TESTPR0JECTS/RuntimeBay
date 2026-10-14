import { mountLoadingOverlay } from './loading-overlay';

const overlay = mountLoadingOverlay('Preparing your workspace…');
window.addEventListener('pagehide', () => overlay.destroy(), { once: true });
