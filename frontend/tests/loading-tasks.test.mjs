import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
const source = readFileSync(new URL('../src/loading/loading-tasks.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { LoadingTasks } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
function harness() { const events=[]; const tasks=new LoadingTasks(state=>events.push(state)); return { tasks, events, current:()=>events.at(-1) }; }
test('overlapping tasks keep the overlay locked until the final owner releases',()=>{
  const h=harness(), a=h.tasks.begin('A'), b=h.tasks.begin('B');
  a(); assert.deepEqual(h.current(),{active:true,message:'B',count:1});
  a(); assert.equal(h.events.length,3);
  b(); assert.deepEqual(h.current(),{active:false,message:'',count:0});
});
test('finishing the newest task restores the remaining task description',()=>{
  const h=harness(), a=h.tasks.begin('A'), b=h.tasks.begin('B');
  b(); assert.equal(h.current().message,'A'); a();
});
test('async work releases its ticket when it rejects',async()=>{
  const h=harness(), scope=h.tasks.scope();
  await assert.rejects(scope.run('Saving',async()=>{throw new Error('Save failed');}),/Save failed/);
  assert.equal(h.current().active,false);
});
test('closing a component scope leaves other component tasks active',()=>{
  const h=harness(), a=h.tasks.scope(), b=h.tasks.scope();
  const late=a.begin('Leaving'), finish=b.begin('Still loading');
  a.close(); assert.deepEqual(h.current(),{active:true,message:'Still loading',count:1});
  late(); assert.equal(h.current().count,1); finish(); assert.equal(h.current().active,false);
});
test('late completion after component destruction cannot unlock a newer operation',async()=>{
  const h=harness(), scope=h.tasks.scope(); let resolve;
  const pending=scope.run('Old page',()=>new Promise(done=>{resolve=done;}));
  scope.close(); const release=h.tasks.begin('New page');
  resolve(); await pending; assert.equal(h.current().message,'New page'); assert.equal(h.current().count,1); release();
});
test('closed scopes do not launch new work or create tickets',async()=>{
  const h=harness(), scope=h.tasks.scope(); scope.close(); let called=false;
  await assert.rejects(scope.run('Invalid',async()=>{called=true;}),/closed/);
  scope.begin('Ignored')(); assert.equal(called,false); assert.equal(h.events.length,0);
});
