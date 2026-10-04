# profanity (working name)

Open-source TypeScript profanity **detection** package. English + Hindi
(Devanagari and romanized Hinglish). MIT, npm-published, zero runtime deps.

The package reports evidence. It never decides. No default threshold, no
default allowlist, no auto-blocking.

---

## How to behave in this repo

These rules override the global `~/.claude/CLAUDE.md` response style for this
project. The override is deliberate: this is a published package where design
mistakes become breaking changes, so reasoning has to be visible.

- **Explain, don't just do.** State what you changed and why. When a change has
  a trade-off, name the trade-off. Caveman bullets are not enough here.
- **Be critical, not agreeable.** If a proposal is wrong, say so first, say why,
  and propose the alternative. Do not open with praise. Do not implement a bad
  idea quietly and hope it gets caught in review.
- **Disagreement is the job.** The user is building a filter that will run
  unattended against real customer reviews. Agreeing with a flawed design costs
  them more than a blunt "this breaks when X".
- **Separate fact from opinion.** Say "this fails neg-044" (verifiable) rather
  than "this feels risky". If something is untested, say it is untested.
- **No invented data.** Test cases come from real reviews or are explicitly
  marked `"source":"synthetic"` and flagged for review. Never pad the corpus
  with plausible-looking strings to make coverage numbers rise.
- **Never claim green tests without running them.** If the suite fails, paste
  the failure.
- **Ask before widening scope.** Fuzzy/Levenshtein matching, ML, sentiment
  detection and auto-blocking are all out of scope.

---

## Architecture

- **Wordlists**: separate JSON per locale — `en`, `hi-deva`, `hi-latin`. Each
  term tagged `severity: "mild" | "severe"`.
- **Normalizer pipeline** (pluggable, ordered):
  1. lowercase
  2. NFKD + strip **Latin** combining marks (U+0300–U+036F) only
  3. Devanagari → Latin transliteration
  4. collapse repeated characters
  5. leetspeak map
  6. strip separators (punctuation, whitespace, emoji, U+200B/C/D, U+FEFF)
- **Matcher**: exact lookup on normalized tokens, then a whole-string scan for
  spaced-out variants (`ch u t1ya`).
- **API**: `detect(input: string, options?): Result`. A string, not an object —
  the consumer picks which of their fields to pass. That is what keeps this
  reusable outside review forms.

```ts
type Result = {
  score: number;
  cleaned: string;
  matches: Array<{
    term: string;          // wordlist entry
    matchedText: string;   // what appeared in the input
    severity: "mild" | "severe";     // property of the WORD
    confidence: "high" | "low";      // property of the MATCH
    method: "exact" | "spaced" | "leet" | "vowel-drop" | "homoglyph";
    lang: "en" | "hi-deva" | "hi-latin";
    index: number;
  }>;
};
```

`severity` and `confidence` stay orthogonal. Do not merge them into one enum
(`severe-review`): severity describes the word, confidence describes the match,
and a combined enum grows as 2×N and breaks every consumer `switch` on each
addition. Consumers who want a single token derive `` `${severity}:${confidence}` ``
in their own code.

---

## Decisions already made

Do not silently revisit these.

- `detect(null)` throws `TypeError`. Rating-only reviews have no text; the
  consumer filters them out.
- Allowlist entries are **phrases**, not bare tokens. A bare `lund` allowlist
  would suppress a real slur seen in production. `"dr lund"` passes; `"lund"`
  alone still matches.
- No default allowlist ships. Allowlists in the test corpus are per-case
  fixtures, not package defaults.
- `fuck` = severe. `lund` = severe.
- `bakwas` / `bakwaas` ("rubbish") is **not** profanity. Same for `worst`,
  `useless`, `stupid`, `time pass` — that is sentiment, not profanity.
- Hindi terms must match **in Devanagari, before transliteration**. `लंड`
  transliterates to `land`, which both misses the slur and collides with the
  English word.
- Step 2 strips Latin marks only. A generic `\p{Mn}` strip destroys Devanagari
  matras and anusvara: `लैंड` (land) and `लंड` (slur) both collapse to `लड`.
- Short stems (`chut`, `chot`, `gand`, `ass`) match as **whole tokens only**,
  never inside the whole-string scan. Otherwise `choti`, `chota`, `Gandhi`,
  `passing` and `classes` all flag.
- Vowel-dropping is **opt-in** (`options.vowelDrop`), `confidence: "low"`. It is
  off by default because `land` → `lnd` and `laundry` → `lndry` collide exactly
  with `lund`. In ~130 real reviews, 2 contained profanity; a noisy matcher on
  by default buries the review queue.
- `*` matches a single character only between letters (`l*nd`, `f*ck`). It is
  never a multi-character wildcard, because reviews are manually masked as
  `L***` upstream and those must pass.
- Wordlist entries may carry `ambiguous: true` (the term itself collides with
  innocent usage: `laura`, `mc`, `bc`, `pussy`, `saala`). A match on an ambiguous
  term is reported with `confidence: "low"`, same as a vowel-drop match. This
  keeps `confidence` a property of the match while letting the wordlist say
  which terms are inherently unreliable.
- Entries may carry `wholeTokenOnly: true`. Short stems get this. It is the only
  thing standing between `ass` and `assignment`.
- Devanagari terms are stored **NFC**. `ड़` exists as U+095C and as U+0921+U+093C;
  without a compose step, half of real input misses.
- The normalizer must return **both** a token array (with boundaries intact, for
  `wholeTokenOnly` and phrases) and a separator-stripped string (for the spaced
  variant scan). One output cannot serve both.
- Wordlist terms run through the same normalizer as the input. Otherwise
  collapse-repeats means `chootiya` in the list can never match anything.

---

## Corpus

`test/fixtures/corpus.jsonl` — one case per line. It is the spec. It was written
before the normalizer and matcher, and it stays that way: **never edit a case to
make the implementation pass.** If a case is wrong, argue it out with the user
first.

```jsonc
{ "id": "pos-001", "input": "...", "expect": [ /* matches */ ],
  "options": { "allowlist": ["..."] },   // optional, per-case
  "source": "real" | "user" | "spec" | "synthetic",
  "phase": "day2",                       // optional: runner skips these
  "note": "why this case exists" }
```

- `expect: []` means the input must pass clean.
- `source: "real"` means it came from the production review table. Those cases
  carry the most weight.
- `phase: "day2"` cases are known gaps (homoglyphs, vowel-drop) and are skipped
  so the suite can be green today.

---

## Out of scope (today, and stated)

- Fuzzy / Levenshtein matching.
- Homoglyph confusables table — needs a new normalizer step.
- Any default blocking behaviour, threshold, or `isProfane` boolean.

## Tooling

tsup + vitest, plain TypeScript, no framework.
