import { spawn } from 'node:child_process';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const launcher = join(process.argv[2], 'Contents/Resources/runtimebay-terminal');
// Keep stdin open: closing it is the supervisor's intentional shutdown signal.
async function check(command, expected) {
  const child = spawn(launcher, command, { stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', bytes => { output += bytes; });
  child.stderr.on('data', bytes => { output += bytes; });
  const deadline = setTimeout(() => child.kill('SIGKILL'), 8000);
  try {
    await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => {
        if (code === 0 && expected.test(output)) resolve();
        else reject(new Error(`Packaged terminal failed (${code ?? signal}): ${output}`));
      });
    });
  } finally { clearTimeout(deadline); child.stdin.destroy(); }
}
assert.ok(process.argv[2], 'Supply the packaged app path');
await check(['/usr/bin/printf', 'RUNTIMEBAY_PACKAGED_PTY_OK'], /RUNTIMEBAY_PACKAGED_PTY_OK/);
await check(['/usr/bin/ssh', '-V'], /OpenSSH_/);
console.log('Packaged PTY and SSH launch checks passed.');
