import { spawn } from 'node:child_process';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const launcher = join(process.argv[2], 'Contents/Resources/runtimebay-terminal');
// Keep stdin open: closing it is the supervisor's intentional shutdown signal.
async function check(command, expected, expectedCode = 0) {
  const child = spawn(launcher, command, { stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', bytes => { output += bytes; });
  child.stderr.on('data', bytes => { output += bytes; });
  const deadline = setTimeout(() => child.kill('SIGKILL'), 8000);
  try {
    await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => {
        if (code === expectedCode && expected.test(output)) resolve();
        else reject(new Error(`Packaged terminal failed (${code ?? signal}): ${output}`));
      });
    });
  } finally { clearTimeout(deadline); child.stdin.destroy(); }
}
assert.ok(process.argv[2], 'Supply the packaged app path');
await check(['/usr/bin/printf', 'RUNTIMEBAY_PACKAGED_PTY_OK'], /RUNTIMEBAY_PACKAGED_PTY_OK/);
await check(['/usr/bin/ssh', '-V'], /OpenSSH_/);
await check([join(process.argv[2], 'Contents/Resources/runtimebay-askpass')], /^$/, 1);
console.log('Packaged PTY, SSH, and askpass launch checks passed.');
