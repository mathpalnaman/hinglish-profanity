# Releasing

One repository, two artifacts, one version number.

| | built from | published to |
|---|---|---|
| JavaScript | `src/` → `dist/` (tsup) | npm, with `npm publish` |
| Java | `java/` (Gradle), reading `src/wordlists/` | Maven Central, as `io.github.mathpalnaman:hinglish-profanity` |

`package.json` is the only place the version is written. The Gradle build reads
it from there.

A Maven Central release is permanent: a version can never be replaced or
deleted. That is why the upload and the release are two separate steps below.

## One-time setup

1. **Central Portal account.** Sign in at <https://central.sonatype.com> with
   GitHub. That verifies the `io.github.mathpalnaman` namespace.
2. **User token.** Account → Generate User Token. It gives a username and a
   password; the password is shown once.
3. **GPG key.** Central requires every file to be signed.

   ```bash
   gpg --full-generate-key                       # RSA 4096, your name and email, a passphrase
   gpg --list-secret-keys --keyid-format long    # note the key id after "rsa4096/"
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>     # the private key, for the step below
   ```

4. **Store the four values.** Never commit them.

   For releases from GitHub Actions, add repository secrets (Settings → Secrets
   and variables → Actions):

   | secret | value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | token username |
   | `MAVEN_CENTRAL_PASSWORD` | token password |
   | `SIGNING_KEY` | the whole armored private key, including the BEGIN/END lines |
   | `SIGNING_PASSWORD` | the key's passphrase |

   For a release from your own machine, put the same values in
   `~/.gradle/gradle.properties` as `mavenCentralUsername`,
   `mavenCentralPassword`, `signingInMemoryKey` (newlines written as `\n`) and
   `signingInMemoryKeyPassword`.

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
   `CHANGELOG.md` entry and update the version in the README's Java snippets.
   Gradle picks the version up; nothing to edit in `java/`.

4. **Commit, tag, push the tag.**

   ```bash
   git commit -am "Release vX.Y.Z"
   git tag vX.Y.Z
   git push origin main vX.Y.Z
   ```

   The tag starts the "Release Java to Maven Central" workflow. It checks the
   tag against `package.json`, runs the tests, signs the jars and uploads them
   to the Central Portal. It does **not** release them.

5. **Release the Java artifact.** Open
   <https://central.sonatype.com/publishing/deployments>. The upload appears as
   a deployment; wait for it to show **Validated**, look at the file list, then
   click **Publish**. It reaches Maven Central within about half an hour.

   If validation fails, click **Drop**, fix the problem, delete and re-push the
   tag. Nothing has been released at that point.

6. **Publish to npm.**

   ```bash
   npm publish
   ```

   `prepublishOnly` runs typecheck, tests and the build first.

To release from your own machine instead of the workflow, run
`cd java && ./gradlew publishToMavenCentral` in place of the tag-triggered
upload; step 5 is the same.

## Checks that should stop a bad release

- `npm pack --dry-run` lists only `dist/`, `README.md`, `LICENSE` and
  `package.json`. Nothing from `java/`, `scripts/` or `test/` ships to npm.
- `HinglishProfanity.WORDLIST_HASH` (Java) equals `WORDLIST_HASH` (JavaScript).
  `WordlistTest` asserts this against the hash recorded in `parity.json`.
- A wordlist that violates `src/wordlists/schema.json` fails the Gradle build.
- The release workflow refuses a tag that does not equal `v` + the
  `package.json` version.
