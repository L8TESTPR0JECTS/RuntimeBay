export type AppearanceMode = 'light' | 'dark' | 'system';
export interface Preferences { version: 1; palette: string; appearance: AppearanceMode; onboardingComplete: boolean }
export interface Palette { id: string; name: string; hue: number; saturation: number }
export const PALETTES: readonly Palette[] = [
  { id: 'harbor', name: 'Harbor', hue: 167, saturation: 54 },
  { id: 'ocean', name: 'Ocean', hue: 213, saturation: 70 },
  { id: 'glacier', name: 'Glacier', hue: 191, saturation: 54 },
  { id: 'forest', name: 'Forest', hue: 137, saturation: 45 },
  { id: 'sage', name: 'Sage', hue: 92, saturation: 32 },
  { id: 'amber', name: 'Amber', hue: 43, saturation: 78 },
  { id: 'copper', name: 'Copper', hue: 20, saturation: 63 },
  { id: 'clay', name: 'Clay', hue: 30, saturation: 30 },
  { id: 'rose', name: 'Rose', hue: 344, saturation: 52 },
  { id: 'plum', name: 'Plum', hue: 305, saturation: 34 },
  { id: 'violet', name: 'Violet', hue: 264, saturation: 58 },
  { id: 'graphite', name: 'Graphite', hue: 220, saturation: 8 }
];
export const DEFAULT_PREFERENCES: Preferences = { version: 1, palette: 'harbor', appearance: 'system', onboardingComplete: false };
export function isPreferences(value: unknown): value is Preferences {
  if (!value || typeof value !== 'object') return false;
  const p = value as Preferences;
  return p.version === 1 && PALETTES.some(a => a.id === p.palette) && ['light', 'dark', 'system'].includes(p.appearance) && typeof p.onboardingComplete === 'boolean';
}
export function themeTokens(palette: Palette, dark: boolean): Record<string, string> {
  const h = palette.hue, s = palette.saturation, tint = Math.min(s, 18);
  const color = (saturation: number, lightness: number) => `hsl(${h} ${saturation}% ${lightness}%)`;
  return {
    '--paper': color(tint, dark ? 9 : 97), '--ink': color(tint, dark ? 93 : 16),
    '--muted': color(tint, dark ? 70 : 35), '--line': color(tint, dark ? 27 : 83),
    '--surface': color(tint, dark ? 13 : 99), '--surface-soft': color(tint, dark ? 17 : 93),
    '--input': color(tint, dark ? 11 : 100), '--teal': color(s, dark ? 70 : 29),
    '--accent': color(s, dark ? 70 : 29), '--accent-hover': color(s, dark ? 77 : 23),
    '--on-accent': color(tint, dark ? 10 : 100), '--focus': color(s, dark ? 70 : 39),
    '--nav': color(Math.min(s, 30), dark ? 6 : 15), '--nav-text': color(tint, 94),
    '--nav-muted': color(tint, 73), '--nav-selected': color(Math.min(s, 30), 25), '--nav-line': color(tint, 33),
    '--terminal': color(tint, 8), '--terminal-bar': color(tint, 13), '--terminal-line': color(tint, 29),
    '--terminal-text': color(tint, 84), '--terminal-muted': color(tint, 69),
    '--success-bg': dark ? 'hsl(145 24% 17%)' : 'hsl(145 33% 91%)',
    '--success-ink': dark ? 'hsl(145 40% 75%)' : 'hsl(145 42% 26%)',
    '--warning-bg': dark ? 'hsl(35 28% 17%)' : 'hsl(35 50% 91%)',
    '--warning-ink': dark ? 'hsl(35 65% 76%)' : 'hsl(35 55% 28%)',
    '--danger-bg': dark ? 'hsl(5 30% 18%)' : 'hsl(5 50% 93%)',
    '--danger-ink': dark ? 'hsl(5 65% 77%)' : 'hsl(5 55% 35%)',
    '--danger-solid': 'hsl(5 55% 35%)', '--overlay': 'rgb(0 0 0 / .4)', '--shadow': 'rgb(0 0 0 / .16)'
  };
}
export function swatch(palette: Palette): string {
  const light = themeTokens(palette, false), dark = themeTokens(palette, true);
  return `conic-gradient(${light['--accent']} 0deg 180deg, ${light['--surface-soft']} 180deg 270deg, ${dark['--nav-selected']} 270deg 360deg)`;
}
