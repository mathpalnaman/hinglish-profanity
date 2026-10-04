# How detection works

`detect(input, options)` never guesses and never decides. It cleans the input,
looks the cleaned text up in three wordlists, and reports what it found along
with where it found it.

## The whole flow

```mermaid
flowchart TD
    A["input: string<br/>(raw review text)"] --> B{"Devanagari<br/>present?"}

    B -- yes --> C["<b>Pass 1: match in Devanagari</b><br/>hi-deva wordlist, NFC-composed<br/><i>before any transliteration</i>"]
    B -- no --> D
    C --> D["<b>Normalizer pipeline</b>"]

    D --> D1["1. lowercase"]
    D1 --> D2["2. NFKD + strip Latin marks<br/>U+0300–U+036F only<br/><i>never Devanagari matras</i>"]
    D2 --> D3["3. Devanagari → Latin<br/>transliteration"]
    D3 --> D4["4. collapse repeated chars<br/>LUNDDD → lund"]
    D4 --> D5["5. leet decode<br/>0→o 3→e 1→i/l @→a $→s"]
    D5 --> E["<b>Two outputs, both with offset maps</b>"]

    E --> E1["<b>tokens[]</b><br/>boundaries kept<br/>for wholeTokenOnly + phrases"]
    E --> E2["<b>stripped string</b><br/>all separators gone<br/>for spaced variants"]

    E1 --> F1["<b>exact</b><br/>token === term"]
    E1 --> F2["<b>phrase</b><br/>multi-word entries"]
    E2 --> F3["<b>spaced</b><br/>substring scan<br/>skips wholeTokenOnly terms"]
    E1 --> F4["<b>vowel-drop</b><br/>opt-in, confidence: low"]

    F1 --> G["candidate matches"]
    F2 --> G
    F3 --> G
    F4 --> G
    C --> G

    G --> H{"overlaps an<br/>allowlist phrase?"}
    H -- yes --> H1["discarded"]
    H -- no --> I["map cleaned positions<br/>back to original offsets"]

    I --> J["<b>DetectResult</b><br/>score · matches[] · cleaned"]
    J --> K["<i>consumer decides</i><br/>block · queue for review · ignore"]

    style C fill:#2d5016,color:#fff
    style J fill:#1a3a5c,color:#fff
    style K fill:#4a3010,color:#fff
    style H1 fill:#5c1a1a,color:#fff
```

## Why the offset map exists

The matcher searches the *cleaned* text, but every reported `index` must point
into the *original* text. They are different strings of different lengths.

```mermaid
flowchart LR
    subgraph orig["original input (10 chars)"]
        O["l · u · n · d · k a<br/>0 1 2 3 4 5 6 7 8 9"]
    end

    subgraph clean["cleaned (6 chars)"]
        C["l u n d k a<br/>0 1 2 3 4 5"]
    end

    subgraph map["offset map"]
        M["cleaned 0 → orig 0<br/>cleaned 1 → orig 2<br/>cleaned 2 → orig 4<br/>cleaned 3 → orig 6"]
    end

    O -->|"normalize"| C
    C -->|"match 'lund' at 0..3"| M
    M -->|"look up"| R["report index 0, endIndex 7<br/>matchedText: 'l u n d'"]

    style R fill:#1a3a5c,color:#fff
```

Without the map, `lund` found at cleaned `0..3` would be reported as original
`0..3`, and the consumer slicing the original gets `"l u "`.

This matters because the transformations are not only deletions:

| transformation | example | length change |
| --- | --- | --- |
| strip separators | `l u n d` → `lund` | 7 → 4 |
| collapse repeats | `LUNDDD` → `lund` | 6 → 4 |
| leet decode | `ch00tiya` → `chootiya` | same, different chars |
| transliterate | `चूतिया` → `chutiya` | 6 → 7 (grows) |

Only plain unobfuscated text has `cleaned.length === input.length` — and that is
the only case where you did not need a profanity filter.

## Why Devanagari is matched first

Transliteration is lossy in a way that breaks both directions:

- `लंड` (slur) transliterates to `land` — which is an ordinary English word, and
  is **not** `lund`, so matching after transliteration both misses the slur and
  invites a false positive.
- `लैंड` (the English word "land" written in Devanagari) is a different string
  from `लंड`, so matching *before* transliteration separates them cleanly.

So hi-deva terms are matched against the raw Devanagari, NFC-composed. Only then
does the text get transliterated for the benefit of the Latin wordlists.
