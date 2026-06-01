import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outFile = path.resolve(
  __dirname,
  '../../src/main/assets/js/vendor/cheerio.bundle.js',
);

// QuickJS has no Node globals. Provide minimal shims via banner + define.
const banner = `(function(globalThis){
'use strict';
var process = globalThis.process || { env: {}, nextTick: function(f){ Promise.resolve().then(f); } };
var global = globalThis;
`;
const footer = `
})(typeof globalThis !== 'undefined' ? globalThis : this);`;

await build({
  entryPoints: [path.resolve(__dirname, 'entry.js')],
  bundle: true,
  format: 'iife',
  target: ['es2020'],
  platform: 'browser',
  legalComments: 'none',
  minify: true,
  define: {
    'process.env.NODE_ENV': '"production"',
  },
  banner: { js: banner },
  footer: { js: footer },
  outfile: outFile,
});

console.log('cheerio bundle written to', outFile);
