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


## [Mihon]
This project was originally forked from Mihon, and, while we keep separate version numbers, we would like to note in our changelog when we merge from upstream, and link to their changelog to try to give appropriate credit.
This project is greatly advantaged by building off all of their work, and their continued contributions!

Merged from v0.19.4 [81871a3](https://github.com/mihonapp/mihon/commit/81871a34694c8e408d907731292b7266c5b993cc)  
Merged from v0.19.3 [89bbdb1](https://github.com/mihonapp/mihon/commit/89bbdb17fb4ed1cbe99c14f389940e0f91093a10)  
Forked from Mihon v0.19.3 [7161bc2](https://github.com/mihonapp/mihon/commit/7161bc2e825bdfd66a1829f7dce42bd0570b1008)

[mihon]: https://github.com/mihonapp/mihon/blob/81871a34694c8e408d907731292b7266c5b993cc/CHANGELOG.md
