import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
const source = readFileSync(new URL('../src/docker-types.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { dockerHostName } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
test('local Docker context IDs render as This Mac without changing their identity', () => {
  for (const name of ['default', 'desktop-linux', 'colima']) {
    const host = { name, transport: 'local' };
    assert.equal(dockerHostName(host), 'This Mac');
    assert.equal(host.name, name);
  }
  assert.equal(dockerHostName({ name: 'Helfer', transport: 'ssh' }), 'Helfer');
  assert.equal(dockerHostName(null), '');
});
