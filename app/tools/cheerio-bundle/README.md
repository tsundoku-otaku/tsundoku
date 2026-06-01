# cheerio-bundle

Bundles the real [cheerio](https://github.com/cheeriojs/cheerio) library into a single IIFE for
the QuickJS-based JS novel plugin runtime. Used by the experimental "Use native cheerio for JS
plugins" toggle in **Settings → Advanced → Extensions**.

The output is committed at `app/src/main/assets/js/vendor/cheerio.bundle.js`. Rebuild only when
bumping the cheerio version.

## Rebuild

```sh
cd app/tools/cheerio-bundle
npm install
npm run build
```

`build.mjs` produces a browser-target (htmlparser2 backend), minified IIFE that sets
`globalThis.__realCheerio = { load }`. A small banner shims `process`/`global` since QuickJS has no
Node globals.

Each QuickJS runtime re-evals the bundle (about 330 KB) because JS heaps are not shared.
In my testing the perf is not noticeable, but it might be on older phones.
