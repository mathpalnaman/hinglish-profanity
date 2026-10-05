# Releasing

One repository, two artifacts, one version number.

| | built from | published by |
|---|---|---|
| JavaScript | `src/` → `dist/` (tsup) | `npm publish` |
| Java | `java/` (Gradle), reading `src/wordlists/` | JitPack, from the git tag |

`package.json` is the only place the version is written. The Gradle build reads
it from there and fails if the tag being built disagrees.

## Steps

1. **Change the wordlists or the code.**
   A matcher or normalizer change goes into JavaScript first, then Java.
   Add the corpus case before the change (see README, Contributing).

2. **Run everything.** Use the Node version in `.nvmrc`: `parity.json` depends
   on the Unicode tables of the runtime that generates it.

   ```bash
   npm test
   npm run gen:parity          # rebuilds dist/, rewrites test/fixtures/parity.json
   cd java && ./gradlew test   # corpus, parity, self-check, schema, thread-safety
   ```

   Commit `parity.json` if it changed. CI regenerates it and fails on a diff.

3. **Bump the version** in `package.json` (and `package-lock.json`:
   `npm version <x.y.z> --no-git-tag-version` does both). Add the
   `CHANGELOG.md` entry. Gradle picks the version up; nothing to edit in `java/`.

4. **Commit, tag, push the tag.**

   ```bash
   git commit -am "Release vX.Y.Z"
   git tag vX.Y.Z
   git push origin main vX.Y.Z
   ```

   JitPack builds the Java artifact the first time someone requests the tag.
   Open <https://jitpack.io/#mathpalnaman/hinglish-profanity> and check that the
   build log is green before announcing the release.

5. **Publish to npm.**

   ```bash
   npm publish
   ```

   `prepublishOnly` runs typecheck, tests and the build first.

## Release candidates

To test the Java artifact before a release, tag `vX.Y.Z-rc.N` while
`package.json` still says `X.Y.Z`. The Gradle version check accepts exactly that
form. Do not `npm publish` a release candidate.

## Checks that should stop a bad release

- `npm pack --dry-run` lists only `dist/`, `README.md`, `LICENSE` and
  `package.json`. Nothing from `java/`, `scripts/` or `test/` ships to npm.
- `HinglishProfanity.WORDLIST_HASH` (Java) equals `WORDLIST_HASH` (JavaScript).
  `WordlistTest` asserts this against the hash recorded in `parity.json`.
- A wordlist that violates `src/wordlists/schema.json` fails the Gradle build.
