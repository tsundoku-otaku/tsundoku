# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed ` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [Unreleased]
### Changed
- Set default of "download chapter images" to true  [@Rojikku](https://github.com/Rojikku) [eee423d](https://github.com/tsundoku-otaku/tsundoku/commit/eee423d14a29ca71e8816350cd4b97a210be1330)
- Removed some defaults from the bottom reader bar  [@Rojikku](https://github.com/Rojikku) [#96](https://github.com/tsundoku-otaku/tsundoku/pull/96)


### Added
- Add Edit mode to edit chapters, fixes to images to make this work [@mrissaoussama](https://github.com/mrissaoussama) [#82](https://github.com/tsundoku-otaku/tsundoku/pull/82)
- Added Excerpt Notes/In-Reader Quotes. Save arbitrary/highlighted text, associated to certain chapters, and allow editing and reordering. Saved to a json file in `quotes`  [@Rojikku](https://github.com/Rojikku) [#96](https://github.com/tsundoku-otaku/tsundoku/pull/96) Improved UI in [#136](https://github.com/tsundoku-otaku/tsundoku/pull/136)

### Improved
- EPUB imports should have their descriptions properly parsed. [@mrissaoussama](https://github.com/mrissaoussama) [#137](https://github.com/tsundoku-otaku/tsundoku/pull/137)

### Fixed 
- Fixed tapping novel name opening a manga search instead of novel [@mrissaoussama](https://github.com/mrissaoussama) [#143](https://github.com/tsundoku-otaku/tsundoku/pull/143)
- Improve HTML file detection which should fix some issues with downloaded content. [@mrissaoussama](https://github.com/mrissaoussama) [#133](https://github.com/tsundoku-otaku/tsundoku/pull/133)
- Don't download next on local novels/epubs [@mrissaoussama](https://github.com/mrissaoussama) [#139](https://github.com/tsundoku-otaku/tsundoku/pull/139)
- Fixed bottom bar resizing [@Rojikku](https://github.com/Rojikku) [#96](https://github.com/tsundoku-otaku/tsundoku/pull/96)
- Fixed extension filter option formatting [@mrissaoussama](https://github.com/mrissaoussama) [#138](https://github.com/tsundoku-otaku/tsundoku/pull/138)
- Refresh library when deleting a category [@Rojikku](https://github.com/Rojikku)  [#134](https://github.com/tsundoku-otaku/tsundoku/pull/134)
- Fixed ttf font importing [@Rojikku](https://github.com/Rojikku)  [#135](https://github.com/tsundoku-otaku/tsundoku/pull/135)

### Other
- Merged Mihon [25d4bf5](https://github.com/mihonapp/mihon/commit/25d4bf5e2ffdcb84f6469f5e0a81108777a98e85) [@Rojikku](https://github.com/Rojikku)


## [v0.1.2] - 2026-03-22
### Changed
- Decode HTML entities in JS manga title/descriptions `I&#x27;m` > `I'm` [@mrissaoussama](https://github.com/mrissaoussama) [#76](https://github.com/tsundoku-otaku/tsundoku/pull/76)
- Support for text selection in TextView [@Rojikku](https://github.com/Rojikku) [@mrissaoussama](https://github.com/mrissaoussama) [#95](https://github.com/tsundoku-otaku/tsundoku/pull/95)

### Improved
- New sorting option based on source name [@mrissaoussama](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)
- Duplicate finder now has category filter [@mrissaoussama](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)
- Hid Quick migration, removed from unnecessary places, added category option [@mrissaoussama](https://github.com/mrissaoussama) [#43](https://github.com/tsundoku-otaku/tsundoku/pull/43)
- Improve library performance when using filters and sorters [@mrissaoussama](https://github.com/mrissaoussama) [#55](https://github.com/tsundoku-otaku/tsundoku/pull/55)
- Add Enable/Disable for Extension Repos [@mrissaoussama](https://github.com/mrissaoussama) [#50](https://github.com/tsundoku-otaku/tsundoku/pull/50)
- Improved novel reader settings UI [@Rojikku](https://github.com/Rojikku) [#49](https://github.com/tsundoku-otaku/tsundoku/pull/49)
- Fix Epub import to add to library (Not just inside source) and add specific category to import [@mrissaoussama](https://github.com/mrissaoussama) [#70](https://github.com/tsundoku-otaku/tsundoku/pull/70)
- Improved novel edit UI [@Rojikku](https://github.com/Rojikku) [#72](https://github.com/tsundoku-otaku/tsundoku/pull/72)
- Add `.nomedia` file to `localnovels` and `lnreader_plugins` dirs [@mrissaoussama](https://github.com/mrissaoussama) [#75](https://github.com/tsundoku-otaku/tsundoku/pull/75)
- Fix whiteflashes with webview on older android [@mrissaoussama](https://github.com/mrissaoussama) [#77](https://github.com/tsundoku-otaku/tsundoku/pull/77)
- EPUB improvements - CSS/JS Support, details.json, description/genre parsing [@mrissaoussama](https://github.com/mrissaoussama) [#77](https://github.com/tsundoku-otaku/tsundoku/pull/77)

### Added
- Toggle under settings > Appearance that hides most manga UI elements[@mrissaoussama](https://github.com/mrissaoussama) [#48](https://github.com/tsundoku-otaku/tsundoku/pull/48)
- Advanced Local Source EPUB volume ordering (Multiple EPUBs in one Novel) [Guide](https://tsundoku-otaku.github.io/docs/guides/local-source/novels) [@mrissaoussama](https://github.com/mrissaoussama) [#71](https://github.com/tsundoku-otaku/tsundoku/pull/71)
- Toggle under settings > Advanced that hides source name under manga details (For screenshots/bug reporting) [@mrissaoussama](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)
- Customizable novel reader bottom toolbar [@Rojikku](https://github.com/Rojikku) [#45](https://github.com/tsundoku-otaku/tsundoku/pull/45)
- Support lnreader repo add link [@mrissaoussama](https://github.com/mrissaoussama) [#47](https://github.com/tsundoku-otaku/tsundoku/pull/47)

### Fixed
- Telemetry [@Rojikku](https://github.com/Rojikku) [d4ec8df](https://github.com/tsundoku-otaku/tsundoku/commit/d4ec8dff90707ddabfb39a49be678e3ccc7e7ba2)
- Updates tab didn't show all items properly when showing one entry per novel [@mrissaoussama](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)
- Improve storagemanager and directory creation to hopefully prevent issues with android 8 [@mrissaoussama](https://github.com/mrissaoussama) [#44](https://github.com/tsundoku-otaku/tsundoku/pull/44) [#56](https://github.com/tsundoku-otaku/tsundoku/pull/56)
- Automatically refresh covers when auto-adding an EPUB to library [@Rojikku](https://github.com/Rojikku) [#80](https://github.com/tsundoku-otaku/tsundoku/pull/80)
- Prevent keyboard overlap with new Edit UI Desc screen [@Rojikku](https://github.com/Rojikku) [#81](https://github.com/tsundoku-otaku/tsundoku/pull/81)

### Other
- Merged Mihon [2f9edb5](https://github.com/mihonapp/mihon/commit/2f9edb551fb2255c11ccd8452a080e87b9c963eb) [@Rojikku](https://github.com/Rojikku)
- Removed `(JS)` from extension add screen, keeping yellow badge [@mrissaoussama](https://github.com/mrissaoussama) [#46](https://github.com/tsundoku-otaku/tsundoku/pull/46)

## [v0.1.1] - 2026-03-17
### Improved
- Optimized Novel Delay Sliders and expanded ranges [@Rojikku](https://github.com/Rojikku) [5e220d2](https://github.com/tsundoku-otaku/tsundoku/commit/5e220d284ed5e71d5c422c1577cfcb0c26c47ff3)
- Database Maintenance can happen in background as WorkerManager job [@mrissaoussama](https://github.com/mrissaoussama) [#32](https://github.com/tsundoku-otaku/tsundoku/pull/32)
- Library clear/clean operations can happen in background as WorkerManager job [@mrissaoussama](https://github.com/mrissaoussama) [#33](https://github.com/tsundoku-otaku/tsundoku/pull/33)
- Added pagination to updates screen (Allows dynamic loading of update history) [@mrissaoussama](https://github.com/mrissaoussama) [#35](https://github.com/tsundoku-otaku/tsundoku/pull/35)
- Added Gemini to translation options [@mrissaoussama](https://github.com/mrissaoussama) [#38](https://github.com/tsundoku-otaku/tsundoku/pull/38)

### Changed
- Corrected release logo color to match scheme with other releases (Lighter color) [@Rojikku](https://github.com/Rojikku) [e3fa1dc](https://github.com/tsundoku-otaku/tsundoku/commit/9eb8e1f0a23c9e3be986dbc657814c5ede4e56a2)

### Fixed
- Added safeguards to prevent Out of Memory issues with library exportation [@mrissaoussama](https://github.com/mrissaoussama) [#36](https://github.com/tsundoku-otaku/tsundoku/pull/36)
- Browse source paging isolation and filter preset icons [@mrissaoussama](https://github.com/mrissaoussama) [#37](https://github.com/tsundoku-otaku/tsundoku/pull/37)
- Switching chapters restores novel scroll position [@mrissaoussama](https://github.com/mrissaoussama) [#39](https://github.com/tsundoku-otaku/tsundoku/pull/39)
- Added Out of Memory safeguards to importer, storage handling fix, and "various fixes" [@mrissaoussama](https://github.com/mrissaoussama) [#40](https://github.com/tsundoku-otaku/tsundoku/pull/40)

### Other
- Merged Mihon [f6b2684](https://github.com/mihonapp/mihon/commit/f6b2684323569ef0eb23e143cc5d65d7cc1aae3c) [@Rojikku](https://github.com/Rojikku)
- Batch track queries in stats screen to fix N+1 [@mrissaoussama](https://github.com/mrissaoussama) [#34](https://github.com/tsundoku-otaku/tsundoku/pull/34)


## [v0.1.0] - 2026-03-06
### Improved
- Add lnreader-based grey color scheme [@Rojikku](https://github.com/Rojikku) [#2](https://github.com/tsundoku-otaku/tsundoku/pull/2)
- Rebrand from Mihon [@Rojikku](https://github.com/Rojikku) [#5](https://github.com/tsundoku-otaku/tsundoku/pull/5)

### Added
- Novel reading features [@mrissaoussama](https://github.com/mrissaoussama)
- Support lnreader JS plugins [@mrissaoussama](https://github.com/mrissaoussama)
- Support user configurable "custom extensions" [@mrissaoussama](https://github.com/mrissaoussama)
- Infinite load reading [@mrissaoussama](https://github.com/mrissaoussama)
- Import lnreader backups [@mrissaoussama](https://github.com/mrissaoussama)
- Download rate limiting options [@mrissaoussama](https://github.com/mrissaoussama)
- EPUB and local novel support [@mrissaoussama](https://github.com/mrissaoussama)
- TTS [@mrissaoussama](https://github.com/mrissaoussama)
- **Translation System**: Chapter translation with configurable engines, smart language detection, real-time translation mode, contextual anchoring, EPUB export, translation queue screen, shared storage, chunk retry, CBZ support, localization
- **Custom Source Builder**: CSS-selector-based source creation, "base on existing extension" cloning, WebView guided wizard, manual editor, import/export/share, test harness, site framework detection (Madara, LightNovelWP, etc.)
- **Duplicate Detection**: Library duplicate finder with sortable FlowRow chips, source priority screen with per-source override rules, tracker-based duplicate detection
- **Quick Migration**: One-tap migration between sources with configurable chapter/category/tracking transfer
- **Mass Import**: Batch import from sources with per-extension throttling overrides, duplicate counting fix
- **Browse Screen Enhancements**: Jump-to-page, page range loading, back confirmation


[Unreleased]: https://github.com/tsundoku-otaku/tsundoku/compare/v0.1.2...main
[v0.1.2]: https://github.com/tsundoku-otaku/tsundoku/compare/v0.1.1...v0.1.2
[v0.1.1]: https://github.com/tsundoku-otaku/tsundoku/compare/v0.1.0...v0.1.1
[v0.1.0]: https://github.com/tsundoku-otaku/tsundoku/compare/5b88f88...v0.1.0

## [Mihon]
This project was originally forked from Mihon, and, while we keep separate version numbers, we would like to note in our changelog when we merge from upstream, and link to their changelog to try to give appropriate credit.
This project is greatly advantaged by building off all of their work, and their continued contributions!

Merged from v0.19.7 [25d4bf5](https://github.com/mihonapp/mihon/commit/25d4bf5e2ffdcb84f6469f5e0a81108777a98e85)  
Merged from v0.19.4 [81871a3](https://github.com/mihonapp/mihon/commit/81871a34694c8e408d907731292b7266c5b993cc)  
Forked from Mihon v0.19.3 [7161bc2](https://github.com/mihonapp/mihon/commit/7161bc2e825bdfd66a1829f7dce42bd0570b1008)

[mihon]: https://github.com/mihonapp/mihon/blob/25d4bf5e2ffdcb84f6469f5e0a81108777a98e85/CHANGELOG.md
