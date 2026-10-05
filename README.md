# hinglish-profanity

Profanity detection for **English, Hindi (Devanagari) and romanized Hinglish**.
Returns evidence, not a boolean. Zero runtime dependencies.

```bash
npm i hinglish-profanity
```

## Why this exists

The leading Hindi-capable profanity filter on npm flags this sentence:

> I bought a new piece of **land** today.

Its Hindi wordlist contains `land` verbatim — because `लंड` (a vulgar Hindi word)
transliterates to `land`, which is also an ordinary English word. Nobody built
that package a false-positive suite, so the bug shipped.

This package matches Devanagari terms **in Devanagari, before transliterating**,
so `लंड` and `लैंड` stay separate. That distinction, and about seventy others
like it, come from a test corpus of real production reviews that was written
before a line of matching logic existed.

## Usage

```ts
import { detect } from "hinglish-profanity";

const result = detect("kya chutiya aadmi hai ye");

result.score;    // 8
result.matches;  // [{ term: "chutiya", matchedText: "chutiya", index: 4,
                 //    endIndex: 11, severity: 4, confidence: "high",
                 //    method: "exact", lang: "hi-latin" }]
result.cleaned;  // "kyachutiyaaadmihaiye"
```

`detect()` takes a **string**, not an object — pass whichever field of your data
you want checked. There is no `isProfane`, no default threshold and no default
allowlist. The package reports; you decide:

```ts
const result = detect(review.text);

if (result.matches.some(m => m.severity >= 3 && m.confidence === "high")) {
  deactivate(review);            // definite abuse
} else if (result.matches.length > 0) {
  queueForHumanReview(review);   // something was found, but it may be innocent
}
```

## The two axes

Most filters collapse "how bad is this word" and "how sure are we" into one
number. They are different questions, so they are different fields.

**`severity: 1 | 2 | 3 | 4`** — how offensive the word is. A property of the
word, never of the match.

| | | examples |
|---|---|---|
| `4` | sexual/incest abuse, slurs | `bsdk`, `madarchod`, `chutiya`, `cunt` |
| `3` | strong profanity | `fuck`, `lund`, `gandu`, `asshole`, `randi` |
| `2` | crude but common | `shit`, `bitch`, `harami`, `crap` |
| `1` | mild, tone only | `damn`, `bloody`, `saala`, `kamina` |

**`confidence: "high" | "low"`** — how reliable *this* match is. `low` means
either the term itself collides with innocent usage (`laura` is a name, `bc` is
also "before Christ", `pussy` is also a cat) or it was found by a lossy method.

So `laura` used as abuse reports `severity: 3, confidence: "low"` — a strongly
offensive word, found unreliably. One number could not say both.

## What it catches

```ts
detect("l u n d ka astrologer")   // spaced out        → method: "spaced"
detect("l.u.n.d")                 // delimiters        → "spaced"
detect("LUNDDD")                  // elongated         → "exact"
detect("lu​nd")              // zero-width space  → "spaced"
detect("m@d@rch0d")               // leetspeak         → "leet"
detect("l*nd")                    // masked letter     → "spaced"
detect("b!tch")                   // masked letter     → "spaced"
detect("भोसड़ीके")                 // Devanagari        → "exact"
detect("behen ke lode")           // multi-word        → "phrase"
detect("piece of shit")           // multi-word        → "phrase"
```

Offsets point into the **original** string, so `input.slice(m.index, m.endIndex)`
always equals `m.matchedText` — even for `l u n d`, where the cleaned text is
three characters shorter.

## What it does not catch

Stated plainly, because a filter that oversells itself is worse than no filter:

- **Terms not on the list.** This is wordlist matching, not a model. There is no
  generalisation and no understanding of context.
- **Abuse without profanity.** "This astrologer is a fraud and a waste of money"
  scores 0. Sarcasm, threats and harassment with clean vocabulary all pass.
  Sentiment classification is a different problem.
- **Homoglyphs.** `fυck` with a Greek upsilon is not detected yet.
- **Dropped vowels, by default.** `fck` needs `{ vowelDrop: true }`.
- **`bc` and `mc` in English text.** "500 BC" reports a `confidence: "low"`
  match. Disambiguating needs context that string matching does not have. The
  test corpus records this as a known failure rather than hiding it.

## Options

Every option is off or empty by default.

```ts
detect(text, {
  langs: ["en"],                    // restrict wordlists; cheapest FP reduction
  allowlist: ["dr lund", "laura"],  // phrases that suppress overlapping matches
  extraWords: [{ term: "...", severity: 3 }],
  vowelDrop: true,                  // see warning below
  weights: { 1: 0, 2: 2, 3: 4, 4: 8 },
});
```

**`allowlist` entries are phrases, not words.** Allowlisting the bare word
`lund` also suppresses genuine abuse — `lund` is both a surname and a slur. Pass
`"dr lund"` or `"sonia lund"` so you exempt the *name*, not the word.

**`vowelDrop` is off for a reason.** Measured on 68 real, clean reviews it
produced 5 false positives: `badiya` → `bloody`, `Chota`/`Chat`/`choti` →
`chutia`, `pass` → `piss`. Every vowel-drop match is reported with
`confidence: "low"`. (It currently also drops `l`, so it cannot reach terms
like `lund` at all — see [KNOWN_ISSUES.md](KNOWN_ISSUES.md).)

**`score`** is the sum of per-match weights (defaults `{1:1, 2:2, 3:4, 4:8}`),
with `confidence: "low"` matches counting half. It is **not** a probability and
not a confidence level — just arithmetic over `matches`, exposed so you can
threshold on one number if you prefer.

## Tests

The corpus (`test/fixtures/corpus.jsonl`) is the specification, and most of it is
negative cases drawn from real reviews:

| | |
|---|---|
| total cases | 122 |
| must be detected | 44 |
| must pass clean | 77 |
| must throw (`null` input) | 1 |
| known gaps (skipped, counted in "must be detected") | 3 |

Measured on the current build:

| | |
|---|---|
| false positives across 68 held-out real reviews | 0 |
| recall on bare slurs, both scripts | 30/30 |
| throughput | ~14,600 reviews/sec |
| bundle | 27 KB, no dependencies |

Words that must never match include `analysis`, `assignment`, `assess`,
`assassin`, `classes`, `cocktail`, `scrapbook`, `therapeutic`, `passing`,
`as well`, `chutney`, `chutki`, `chota`, `choti`, `chot`, `chhod`, `ganda`,
`Gandhi`, `land`, `laundry`, `Bhandari`, `Shitij`, `Dikshit`.

```bash
npm test
```

## Java

The same detector is available for the JVM (Java 17+, zero dependencies), built
from this repository and served by [JitPack](https://jitpack.io/#mathpalnaman/hinglish-profanity).

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.mathpalnaman:hinglish-profanity:v0.2.0'
}
```

```java
import io.github.mathpalnaman.hinglishprofanity.*;

Detector detector = HinglishProfanity.defaults();   // build once, share across threads
Result result = detector.detect("kya chutiya aadmi hai ye");
Match m = result.matches().get(0);                   // term "chutiya", index 4, endIndex 11
boolean definite = m.severity() >= 3 && m.confidence() == Confidence.HIGH;
```

Results are **identical** to the JavaScript package of the same version: the
Java build reads the same wordlist files, and CI replays about 4,700 recorded
JavaScript results against it and compares every field. `index` and `endIndex`
are UTF-16 code units in both, so they work with `String.substring` as they do
with `String.prototype.slice`.

Options go through a builder, and a `Detector` is immutable once built:

```java
Detector detector = HinglishProfanity.builder()
    .langs(Set.of(Lang.EN, Lang.HI_LATIN))
    .allowlist(List.of("dr lund"))
    .extraWords(List.of(new Entry("someword", 3)))
    .build();
```

Differences from the JavaScript API, all deliberate:

- `detect(null)` throws `NullPointerException` (JavaScript throws `TypeError`).
- `Confidence` and `Method` are enums. `jsValue()` returns the JavaScript string
  (`"high"`, `"vowel-drop"`) and `fromJsValue()` parses it back, for storing
  matches as JSON.
- `HinglishProfanity.VERSION` and `WORDLIST_HASH` equal the JavaScript exports
  of the same names when both were built from the same commit.

Java 17 ships older Unicode tables than current Node. For a character added to
Unicode after version 13, `cleaned` can differ between the two; the cases found
so far are listed in `ParityTest`.

## Contributing

Wordlist additions are welcome, with two rules:

1. **Add a test case first.** A term without a corpus case will be reverted.
2. **Add the negative too.** If your term collides with an innocent word or a
   name, add that case as well and mark the entry `ambiguous` or
   `wholeTokenOnly`. Recall is easy; not flagging real people is the hard part.

Never edit an existing case to make an implementation pass.

A wordlist or matcher change must keep both implementations in step: run
`npm run gen:parity` and `cd java && ./gradlew test`. See
[RELEASING.md](RELEASING.md). Bugs that are known and deliberately not yet
fixed are in [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

## License

MIT © Naman Mathpal
