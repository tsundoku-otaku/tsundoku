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
### Improved
- New sorting option based on source name [@mrissaoussamau](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)
- Duplicate finder now has category filter [@mrissaoussamau](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)

### Added
- Toggle under settings > Advanced that hides source name under manga details (For screenshots/bug reporting) [@mrissaoussamau](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)

### Fixed
- Updates tab didn't show all items properly when showing one entry per novel [@mrissaoussamau](https://github.com/mrissaoussama) [#41](https://github.com/tsundoku-otaku/tsundoku/pull/41)

## [v0.1.1] - 2026-03-17
### Improved
- Optimized Novel Delay Sliders and expanded ranges [@Rojikku](https://github.com/Rojikku) [5e220d2](https://github.com/tsundoku-otaku/tsundoku/commit/5e220d284ed5e71d5c422c1577cfcb0c26c47ff3)
- Database Maintenance can happen in background as WorkerManager job [@mrissaoussamau](https://github.com/mrissaoussama) [#32](https://github.com/tsundoku-otaku/tsundoku/pull/32)
- Library clear/clean operations can happen in background as WorkerManager job [@mrissaoussamau](https://github.com/mrissaoussama) [#33](https://github.com/tsundoku-otaku/tsundoku/pull/33)
- Added pagination to updates screen (Allows dynamic loading of update history) [@mrissaoussamau](https://github.com/mrissaoussama) [#35](https://github.com/tsundoku-otaku/tsundoku/pull/35)
- Added Gemini to translation options [@mrissaoussamau](https://github.com/mrissaoussama) [#38](https://github.com/tsundoku-otaku/tsundoku/pull/38)

### Changed
- Corrected release logo color to match scheme with other releases (Lighter color) [@Rojikku](https://github.com/Rojikku) [e3fa1dc](https://github.com/tsundoku-otaku/tsundoku/commit/9eb8e1f0a23c9e3be986dbc657814c5ede4e56a2)

### Fixed
- Added safeguards to prevent Out of Memory issues with library exportation [@mrissaoussamau](https://github.com/mrissaoussama) [#36](https://github.com/tsundoku-otaku/tsundoku/pull/36)
- Browse source paging isolation and filter preset icons [@mrissaoussamau](https://github.com/mrissaoussama) [#37](https://github.com/tsundoku-otaku/tsundoku/pull/37)
- Switching chapters restores novel scroll position [@mrissaoussamau](https://github.com/mrissaoussama) [#39](https://github.com/tsundoku-otaku/tsundoku/pull/39)
- Added Out of Memory safeguards to importer, storage handling fix, and "various fixes" [@mrissaoussamau](https://github.com/mrissaoussama) [#40](https://github.com/tsundoku-otaku/tsundoku/pull/40)

### Other
- Merged Mihon [f6b2684](https://github.com/mihonapp/mihon/commit/f6b2684323569ef0eb23e143cc5d65d7cc1aae3c) [@Rojikku](https://github.com/Rojikku)
- Batch track queries in stats screen to fix N+1 [@mrissaoussamau](https://github.com/mrissaoussama) [#34](https://github.com/tsundoku-otaku/tsundoku/pull/34)


## [v0.1.0] - 2026-03-06
### Improved
- Add lnreader-based grey color scheme [@Rojikku](https://github.com/Rojikku) [#2](https://github.com/tsundoku-otaku/tsundoku/pull/2)
- Rebrand from Mihon [@Rojikku](https://github.com/Rojikku) [#5](https://github.com/tsundoku-otaku/tsundoku/pull/5)

### Added
- Novel reading features [@mrissaoussamau](https://github.com/mrissaoussama)
- Support lnreader JS plugins [@mrissaoussamau](https://github.com/mrissaoussama)
- Support user configurable "custom extensions" [@mrissaoussamau](https://github.com/mrissaoussama)
- Infinite load reading [@mrissaoussamau](https://github.com/mrissaoussama)
- Import lnreader backups [@mrissaoussamau](https://github.com/mrissaoussama)
- Download rate limiting options [@mrissaoussamau](https://github.com/mrissaoussama)
- EPUB and local novel support [@mrissaoussamau](https://github.com/mrissaoussama)
- TTS [@mrissaoussamau](https://github.com/mrissaoussama)
- **Translation System**: Chapter translation with configurable engines, smart language detection, real-time translation mode, contextual anchoring, EPUB export, translation queue screen, shared storage, chunk retry, CBZ support, localization
- **Custom Source Builder**: CSS-selector-based source creation, "base on existing extension" cloning, WebView guided wizard, manual editor, import/export/share, test harness, site framework detection (Madara, LightNovelWP, etc.)
- **Duplicate Detection**: Library duplicate finder with sortable FlowRow chips, source priority screen with per-source override rules, tracker-based duplicate detection
- **Quick Migration**: One-tap migration between sources with configurable chapter/category/tracking transfer
- **Mass Import**: Batch import from sources with per-extension throttling overrides, duplicate counting fix
- **Browse Screen Enhancements**: Jump-to-page, page range loading, back confirmation


[Unreleased]: https://github.com/tsundoku-otaku/tsundoku/compare/v0.1.1...main
[v0.1.1]: https://github.com/tsundoku-otaku/tsundoku/compare/v0.1.0...v0.1.1
[v0.1.0]: https://github.com/tsundoku-otaku/tsundoku/compare/5b88f88...v0.1.0

## [Mihon]
This project was originally forked from Mihon, and, while we keep separate version numbers, we would like to note in our changelog when we merge from upstream, and link to their changelog to try to give appropriate credit.
This project is greatly advantaged by building off all of their work, and their continued contributions!

Merged from v0.19.4 [f6b2684](https://github.com/mihonapp/mihon/commit/f6b2684323569ef0eb23e143cc5d65d7cc1aae3c)
Merged from v0.19.4 [81871a3](https://github.com/mihonapp/mihon/commit/81871a34694c8e408d907731292b7266c5b993cc)  
Forked from Mihon v0.19.3 [7161bc2](https://github.com/mihonapp/mihon/commit/7161bc2e825bdfd66a1829f7dce42bd0570b1008)

[mihon]: https://github.com/mihonapp/mihon/blob/f6b2684323569ef0eb23e143cc5d65d7cc1aae3c/CHANGELOG.md
