import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
const source = readFileSync(new URL('../src/palettes.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { PALETTES, themeTokens, isPreferences, DEFAULT_PREFERENCES, fontScale } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
function luminance(css) {
  const [, hue, saturation, lightness] = css.match(/hsl\(([\d.]+) ([\d.]+)% ([\d.]+)%\)/);
  const h = Number(hue) / 60, s = Number(saturation) / 100, l = Number(lightness) / 100;
  const c = (1 - Math.abs(2*l - 1))*s, x = c*(1 - Math.abs(h%2 - 1)), m = l-c/2;
  const rgb = [[c,x,0],[x,c,0],[0,c,x],[0,x,c],[x,0,c],[c,0,x]][Math.floor(h)%6];
  return rgb.map(v => v+m).map(v => v <= .04045 ? v/12.92 : ((v+.055)/1.055)**2.4).reduce((sum,v,i) => sum+v*[.2126,.7152,.0722][i],0);
}
function contrast(a,b) { const [hi,lo] = [luminance(a),luminance(b)].sort((a,b)=>b-a); return (hi+.05)/(lo+.05); }
test('all twelve palettes meet text contrast in both appearances', () => {
  assert.equal(PALETTES.length,12); assert.equal(new Set(PALETTES.map(p=>p.id)).size,12);
  for (const palette of PALETTES) for (const dark of [false,true]) {
    const t=themeTokens(palette,dark);
    assert.notEqual(t['--accent'], t['--loading-highlight'], `${palette.id} loading colors must differ`);
    for (const [foreground,background] of [['--ink','--paper'],['--muted','--paper'],['--muted','--surface-soft'],['--on-accent','--accent'],['--nav-muted','--nav'],['--terminal-muted','--terminal-bar'],['--success-ink','--success-bg'],['--warning-ink','--warning-bg'],['--danger-ink','--danger-bg']]) {
      assert.ok(contrast(t[foreground],t[background]) >= 4.5, `${palette.id}/${dark?'dark':'light'} ${foreground} contrast ${contrast(t[foreground],t[background])}`);
    }
  }
});
test('appearance preferences reject unsupported or malformed selections', () => {
  assert.ok(isPreferences(DEFAULT_PREFERENCES));
  for(const update of [{palette:'unknown'},{appearance:'auto'},{version:2},{onboardingComplete:'true'},{suggestionsEnabled:'true'}]) assert.equal(isPreferences({...DEFAULT_PREFERENCES,...update}),false);
  assert.ok(isPreferences({ ...DEFAULT_PREFERENCES, suggestionsEnabled: false }));
  assert.equal(isPreferences(null),false);
});
test('font sizes are bounded whole numbers and text scale is independent of page zoom', () => {
  for (const fontSize of [12, 14, 18, 20]) assert.ok(isPreferences({ ...DEFAULT_PREFERENCES, fontSize }));
  for (const fontSize of [11, 21, 14.5, null, '18', Infinity, NaN, undefined]) {
    assert.equal(isPreferences({ ...DEFAULT_PREFERENCES, fontSize }), false);
  }
  assert.equal(fontScale(14), 1);
  assert.equal(fontScale(20), 20 / 14);
  assert.equal(fontScale(12), 12 / 14);
  assert.equal(fontScale(NaN), 1);
});
