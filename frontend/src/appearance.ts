import { signal } from '@angular/core';
import { DEFAULT_PREFERENCES, isPreferences, Preferences, AppearanceMode, PALETTES, themeTokens, swatch } from './palettes';

export class Appearance {
  readonly palettes = PALETTES;
  readonly swatch = swatch;
  preferences = signal<Preferences>({ ...DEFAULT_PREFERENCES });
  open = signal(false);
  loading = signal(true);
  saving = signal(false);
  error = signal('');
  private media = matchMedia('(prefers-color-scheme: dark)');
  private pending: Preferences | null = null;
  private disposed = false;
  private controller = new AbortController();
  private previousOverflow = '';
  private previousFocus: HTMLElement | null = null;
  private mediaListener = () => this.apply();
  private menuListener = () => this.show();
  private keyListener = (event: KeyboardEvent) => {
    if (!this.open()) return;
    if (event.key === 'Escape') { event.preventDefault(); this.close(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.appearance-drawer button:not([disabled])'));
      if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls.at(-1)?.focus(); }
      else if (!event.shiftKey && document.activeElement === controls.at(-1)) { event.preventDefault(); controls[0]?.focus(); }
    }
  };
  constructor(private session: string) {
    this.apply();
    this.media.addEventListener('change', this.mediaListener);
    window.addEventListener('runtimebay:customize', this.menuListener);
    window.addEventListener('keydown', this.keyListener);
    void this.load();
  }
  private async api(method = 'GET', preferences?: Preferences): Promise<Preferences> {
    const response = await fetch('/api/preferences', { method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + this.session, ...(preferences ? { 'Content-Type': 'application/json' } : {}) },
      ...(preferences ? { body: JSON.stringify(preferences) } : {}) });
    const value = await response.json();
    if (!response.ok) throw new Error(value.error || 'Could not save appearance');
    if (!isPreferences(value)) throw new Error('Appearance settings could not be read');
    return value;
  }
  private async load() {
    try {
      const preferences = await this.api();
      if (this.disposed) return;
      this.preferences.set(preferences); this.apply();
      if (!preferences.onboardingComplete) this.show();
    } catch (e) { if (!this.disposed) this.error.set((e as Error).message); }
    finally { this.loading.set(false); }
  }
  show() {
    if (this.open()) return;
    this.previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden';
    this.open.set(true);
    setTimeout(() => document.querySelector<HTMLElement>('.appearance-drawer button')?.focus(), 0);
  }
  close() {
    this.change({ onboardingComplete: true }); this.open.set(false); document.body.style.overflow = this.previousOverflow;
    setTimeout(() => this.previousFocus?.focus(), 0);
  }
  choosePalette(palette: string) { if (PALETTES.some(p => p.id === palette)) this.change({ palette }); }
  chooseMode(appearance: AppearanceMode) { this.change({ appearance }); }
  private change(update: Partial<Preferences>) {
    if (this.loading()) return;
    const next = { ...this.preferences(), ...update };
    this.preferences.set(next); this.apply(); this.pending = next;
    if (!this.saving()) void this.flush();
  }
  retry() { this.pending = this.preferences(); if (!this.saving()) void this.flush(); }
  private async flush() {
    this.saving.set(true); this.error.set('');
    try {
      // Serialize writes, coalescing quick clicks; an old response never replaces the live preview.
      while (this.pending && !this.disposed) {
        const next = this.pending; this.pending = null;
        await this.api('PUT', next);
      }
    } catch (e) { if (!this.disposed) this.error.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  private apply() {
    const preferences = this.preferences(), dark = preferences.appearance === 'dark' || preferences.appearance === 'system' && this.media.matches;
    const root = document.documentElement;
    root.dataset['appearance'] = dark ? 'dark' : 'light'; root.dataset['palette'] = preferences.palette;
    root.style.colorScheme = dark ? 'dark' : 'light';
    const palette = PALETTES.find(p => p.id === preferences.palette) || PALETTES[0];
    for (const [name, value] of Object.entries(themeTokens(palette, dark))) root.style.setProperty(name, value);
    window.webkit?.messageHandlers.runtimeBay.postMessage({ action: 'appearance', mode: preferences.appearance });
  }
  destroy() {
    this.disposed = true; this.controller.abort(); document.body.style.overflow = this.previousOverflow;
    this.media.removeEventListener('change', this.mediaListener);
    window.removeEventListener('runtimebay:customize', this.menuListener);
    window.removeEventListener('keydown', this.keyListener);
  }
}
