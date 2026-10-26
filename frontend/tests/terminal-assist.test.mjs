import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(new URL('../src/terminal-assist.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { pathSuggestionLabel } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

test('path suggestion labels show the complete remaining component, not just its tail', () => {
  assert.equal(pathSuggestionLabel('Ki', 'tchen/'), 'Kitchen/');
  assert.equal(pathSuggestionLabel('~/Documents/Dev/Ki', 'tchen/'), 'Kitchen/');
  assert.equal(pathSuggestionLabel('~/Documents/Dev/', 'RuntimeBay/'), 'RuntimeBay/');
  assert.equal(pathSuggestionLabel('', 'Documents/'), 'Documents/');
});
