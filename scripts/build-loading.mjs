import { build } from 'esbuild';
import { copyFile, mkdir } from 'node:fs/promises';
await mkdir('.build', { recursive: true });
await build({ entryPoints: ['frontend/src/loading/startup.ts'], outfile: '.build/loading-startup.js', bundle: true, format: 'iife', target: 'safari17', minify: true, legalComments: 'eof' });
await copyFile('frontend/src/loading/loading.css', '.build/loading.css');
