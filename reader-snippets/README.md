# Novel WebView reader snippets

Console snippets for exercising the novel WebView reader features/fixes on the
`fix/reader-lifecycle-leaks` branch. Paste one into the reader's snippet runner
(overflow menu -> snippet row) while a novel chapter is open.

The debug panel lives separately in `../PR_DESCRIPTIONS.md` (a singleton dump of the
`Tsundoku` runtime object, autoscroll/TTS state, the `Android` bridge, and chapter DOM).

## Prerequisite for the devtools snippets

The dialog / file-chooser / console snippets need the opt-in WebChromeClient:

- **Advanced settings -> `novelWebViewDevTools` = ON** — installs the chrome client
  (off by default; some OEM WebView builds break compat, hence the gate).
- **`novelConsoleErrorToast` = ON** — additionally routes `console.log/warn/error` to toasts.

| Snippet | Feature / fix it tests | Prereq |
| --- | --- | --- |
| `console-toast.js` | `onConsoleMessage` toast + level gate | devtools + console toast |
| `js-dialogs.js` | `onJsAlert` / `onJsConfirm` / `onJsPrompt` wired to reader actions | devtools |
| `set-background-image.js` | `onShowFileChooser` -> pick image, set as background, persist to `localStorage['tsundoku:customBg']` | devtools |
| `menu-fab.js` | `Tsundoku.runtime.menuVisible` + `tsundoku:menuvisibilitychange` event: FABs mirror the reader menu | none |
| `find-replace.js` | Bottom overlay (safe-area aware): find-in-chapter with Next/Prev + Replace / Replace all | none |
| `infscroll-append-probe.js` | DocState / `loadedChapterIds` dedup: no duplicate chapter on rapid `loadNextChapter` | infinite scroll ON |

## Menu visibility + safe-area insets

Two separate mechanisms keep content clear of the reader chrome:

- **Novel status bar** — has its own **layout space**: `viewer_container` is padded by its height on
  the docked edge, so the WebView/TextView genuinely shrink and content never renders under it.
  Nothing in JS needs to account for it.
- **Reader menu bars** (top app bar + bottom bar) — transient overlays shown only with the menu, so
  they can't take layout space without resizing the WebView on every toggle. Their measured heights
  (plus system bars) are exposed to JS instead:
  - `Tsundoku.runtime.menuVisible` — boolean, current reader-menu state.
  - `window` event `tsundoku:menuvisibilitychange` — fired on change. `reader-chrome.js` sends
    `detail.visible`; the reader-event system sends `detail.menuVisible` — read either.
  - CSS vars `--tsundoku-safe-top` / `--tsundoku-safe-bottom` — px height of the top/bottom menu bar
    (0 while the menu is hidden). Position fixed elements with e.g.
    `bottom: calc(8px + var(--tsundoku-safe-bottom, 0px))`, or bound a column top **and** bottom to
    sit in the free band between the bars (see `menu-fab.js`).

All bars in these snippets and the debug panel already use those vars.

## Notes

- `set-background-image.js` downscales the chosen image (canvas, longest edge 1280px,
  JPEG q0.82) before storing, so a multi-MB photo fits localStorage's ~5 MB budget.
  It uses one **fixed** key (overwrite, never accumulate) and re-applies on each run,
  so the background survives chapter loads and infinite-scroll appends.
- `tsundoku:*` CustomEvents dispatched on `window`: `tsundoku:menuvisibilitychange`,
  `tsundoku:chapternavigate`, `tsundoku:chapterloading`, `tsundoku:ttsstatechange`,
  `tsundoku:progresschange`. The debug panel's event tap also listens to native `scrollend` /
  `visibilitychange` / `resize`.
- Reading progress: read `Tsundoku.runtime.chapterProgress` / `.progress` (0..1, set every scroll
  frame by the scroll tracker) or subscribe to `tsundoku:progresschange`
  (`detail { progress, chapterProgress, chapterId, isLast }`) instead of re-deriving % from
  `scrollTop`, which drifts under infinite scroll / wide-viewport. `chapterProgress` is progress
  through the current chapter (drives the slider); `progress` is the whole loaded document.
- `find-replace.js` pins to the bottom via `bottom: calc(8px + var(--tsundoku-safe-bottom, 0px))`,
  so it never hides under the reader's bottom bar; matching is literal + case-insensitive and scoped
  to `tsundoku-chapter` content.
