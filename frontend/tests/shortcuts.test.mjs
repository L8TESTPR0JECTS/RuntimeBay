import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
const source = readFileSync(new URL('../src/shortcuts.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { workspaceShortcut, adjacentTerminal } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const key = (key, overrides = {}) => ({ key, code: '', metaKey: true, ctrlKey: false, altKey: false, shiftKey: false, repeat: false, defaultPrevented: false, isComposing: false, ...overrides });
test('commands accept exact modifiers and leave Control, Option, repeats, and consumed events alone', () => {
  assert.equal(workspaceShortcut(key('t')), 'new-terminal');
  for (const overrides of [{ ctrlKey: true }, { altKey: true }, { shiftKey: true }, { metaKey: false }, { repeat: true }, { defaultPrevented: true }, { isComposing: true }]) {
    assert.equal(workspaceShortcut(key('t', overrides)), null);
  }
  for (const character of ['c', 'v', 'a', 'z', 'q', 'h', 'm', ' ', 's']) assert.equal(workspaceShortcut(key(character)), null);
  assert.equal(workspaceShortcut(key('o')), 'import-workflow');
  assert.equal(workspaceShortcut(key('k')), 'import-workflow');
});
test('terminal navigation recognizes shifted bracket keys and wraps without closing shells', () => {
  assert.equal(workspaceShortcut(key('{', { code: 'BracketLeft', shiftKey: true })), 'previous-terminal');
  assert.equal(workspaceShortcut(key('}', { code: 'BracketRight', shiftKey: true })), 'next-terminal');
  assert.equal(workspaceShortcut(key('[', { code: 'BracketLeft' })), null);
  assert.equal(adjacentTerminal(['a', 'b'], 'b', 1), 'a');
  assert.equal(adjacentTerminal(['a', 'b'], 'a', -1), 'b');
  assert.equal(adjacentTerminal([], null, 1), null);
  assert.equal(adjacentTerminal(['a'], 'a', 1), 'a');
  assert.equal(adjacentTerminal(['a', 'b'], null, -1), 'b');
  assert.equal(adjacentTerminal(['a', 'b'], 'missing', 1), 'a');
});
