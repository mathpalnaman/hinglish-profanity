# Changelog

## 0.2.0

### Added

- **Java port** (`java/`, Java 17+, zero dependencies), published through
  JitPack from the same git tags as the npm package. It reads the same wordlist
  files and returns the same results as the JavaScript package: CI replays
  about 4,700 recorded JavaScript results against it. See README, "Java".
- `VERSION` and `WORDLIST_HASH` exports (JavaScript), and
  `HinglishProfanity.VERSION` / `WORDLIST_HASH` (Java). Two builds with the same
  `WORDLIST_HASH` were built from the same wordlists.
- `KNOWN_ISSUES.md`: bugs found while porting, with reproductions. They are
  present in both languages in this release on purpose and are scheduled for
  0.2.1.
- `RELEASING.md`.

### Changed

- No behaviour change in the JavaScript package. `detect()` returns exactly what
  0.1.0 returned.
- Wordlists and fixtures are now stored with LF line endings (`.gitattributes`),
  so `WORDLIST_HASH` is the same on every platform.

### Fixed (documentation only)

- README said `laura` is severity 4; the wordlist has always said 3.
- README and the schema claimed things about the normalizer that the code does
  not do: that `land` → `lnd` collides with `lund` under `vowelDrop` (it does
  not, see KNOWN_ISSUES.md), and that a wordlist term "must survive the
  normalizer unchanged" (no term containing `l` does; the real rule is that a
  term must detect itself, which is now tested).
- `docs/pipeline.md` drew dedupe before the allowlist; the code applies the
  allowlist first.
- README corpus counts corrected (122 cases: 44 positive, 77 negative, 1 contract).
- Stale `note` on the `choot` wordlist entry.

## 0.1.0

Initial release.
