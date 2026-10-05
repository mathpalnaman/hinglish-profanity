# Known issues

Bugs that are known, reproduced, and **deliberately not fixed in 0.2.0**.

0.2.0 adds the Java port. Its job is to return exactly what the JavaScript
package returns, and the parity test (`test/fixtures/parity.json`, about 4,700
recorded results) checks that field by field. Fixing a bug while porting would
have made the two implementations differ on purpose, with nothing left to prove
they agree everywhere else. So every bug below exists **identically in both
languages**, and each Java site carries a comment pointing here.

They are planned for 0.2.1, in this order: fix in JavaScript, port the fix to
Java, regenerate `parity.json`. Each fix needs a corpus case first.

All outputs below were produced by `detect()` on 0.2.0 with default options
unless stated. Java returns the same values.

---

## P1 — `endIndex` splits a surrogate pair

A match whose last character is outside the Basic Multilingual Plane (two UTF-16
code units) ends one code unit early. Styled "mathematical" letters are a common
way to dodge filters, and they are all astral.

```js
detect("𝐟𝐮𝐜𝐤 you").matches[0]
```

| | current | expected |
|---|---|---|
| `index` | `0` | `0` |
| `endIndex` | `7` | `8` |
| `matchedText` | `"𝐟𝐮𝐜\ud835"` (ends in a lone surrogate) | `"𝐟𝐮𝐜𝐤"` |

The word is detected and `term`, `severity` and `score` are right. Only the end
offset is wrong, which matters to anything that highlights or masks the span:
slicing at `7` cuts `𝐤` in half.

**Cause.** Two places compute the end as "start of the last character + 1":
`tokenize()` uses the length of the *normalized* character (`k`, one unit)
rather than the original (`𝐤`, two units), and `matchStripped()` /
`matchDevanagari()` / `allowedRanges()` add a literal `1`.

**Fix.** Carry each cell's original length alongside its source index and use
it for the end offset.

**Until then.** If `matchedText` ends in a high surrogate
(`0xD800`–`0xDBFF`), extend `endIndex` by one.

---

## P2 — `extraWords` phrases never match with their spaces

```js
const opts = { extraWords: [{ term: "bad word", severity: 2 }] };
detect("what a bad word", opts).matches   // current: []            expected: one match, "bad word"
detect("what a badword", opts).matches    // current: one match     expected: []
```

**Cause.** Built-in phrases are normalized word by word. An `extraWords` entry
is normalized as one string, which strips the space, leaving the single part
`badword`.

**Fix.** Normalize `extraWords` phrases word by word, like the built-in ones.

**Until then.** Multi-word `extraWords` entries do not work; add each word
separately if that is acceptable.

---

## P2 — a literal U+0001 in the input acts as a wildcard

The normalizer marks a masked letter (`l*nd`) with U+0001 internally. It does
not remove U+0001 from the input first, so the control character matches any
letter.

```js
detect("l\u0001nd").matches[0].term          // current: "lund"   expected: no match
detect("\u0001\u0001\u0001").matches[0].term  // current: "चूत"    expected: no match
```

The second case reports a Devanagari slur for three control characters, because
the Devanagari pass also treats U+0001 as "any character".

**Fix.** Strip U+0001 from the input before the pipeline runs. (Stripping it,
not treating it as a mask character: nobody types it as obfuscation.)

---

## P3 — the nukta transliteration table is dead code

`normalize.ts` lists nukta consonants (`ड़` → `d`, `ज़` → `z`, `फ़` → `f`) keyed
by two code points. The lookup is per code point, so those keys never match:
the base consonant is transliterated on its own, gets an inherent `a`, and the
nukta is dropped.

| input | current | expected |
|---|---|---|
| `भोसड़ीके` | `bhosadaike` | `bhosdike` (or at least `bhosadike`) |
| `ज़रा` | `jara` | `zara` |
| `फ़ोन` | `phaon` | `fon` |

This affects only the Latin-list fallback for Devanagari input. The Devanagari
wordlist is matched before transliteration and is unaffected (`भोसड़ीके` is
detected, as `hi-deva`).

---

## P3 — vowel-drop treats `l` as a vowel

`vowelDrop` runs after leet decoding, where `l` has already been folded to `i`.
`i` is a vowel, so every `l` is dropped too.

```js
detect("lnd",    { vowelDrop: true }).matches   // current: []   expected: lund (low)
detect("badiya", { vowelDrop: true }).matches   // current: bloody (low) — "bloody" and "badiya" both reduce to "bdy"
```

Terms whose consonants are mostly `l` (`lund`, `loda`) are unreachable by
vowel-drop. Fixing this makes the `land` → `lnd` → `lund` collision real, which
is the reason vowel-drop is opt-in; it needs corpus cases on both sides first.

---

## P3 — `langs: []` means two different things

```js
detect("fuck zzz", { langs: [] }).matches
// current: fuck                       — empty list treated as "all languages"
detect("fuck zzz", { langs: [], extraWords: [{ term: "zzz", severity: 1 }] }).matches
// current: zzz only                   — empty list treated as "no built-in lists"
```

**Fix.** Pick one meaning. "No built-in lists, extraWords only" is the useful
one; it should hold with or without `extraWords`.

The Java builder mirrors this: `langs(Set.of())` behaves the same way.

---

## P3 — weights are not validated

```js
detect("fuck", { weights: { 3: NaN } }).score   // current: NaN   expected: TypeError at call time
```

Negative weights are accepted too. **Fix.** Reject weights that are not finite
numbers ≥ 0. In Java, the same check belongs in `Detector.Builder.weights`.

---

## Not a bug, but worth knowing

- **A collapsed run reports only its first character.** `fuckkk` reports
  `[0, 4)` with `matchedText: "fuck"`, and `LUNDDD` reports `"LUND"`. The slice
  is consistent with the offsets; it just does not cover the repeated tail.
- **`method` is inferred from the raw text**, not from which pass found the
  match, so `l*nd` and `b!tch` report `"spaced"`.
- **The allowlist is applied before dedupe.** Allowlisting `mother` against
  `motherfucker` removes that match and then reports the inner `fuck`.
  `docs/pipeline.md` showed the opposite order until 0.2.0.
- **Nothing enforces that an allowlist entry is a phrase.** A bare `lund`
  suppresses every `lund`. That is a documented convention, not a check.
