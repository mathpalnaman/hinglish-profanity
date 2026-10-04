/**
 * Public types for hinglish-profanity.
 *
 * Everything exported here is part of the package's API contract. Changing a
 * field name or narrowing a union in this file is a breaking change.
 */

/** Which wordlist a term came from. */
export type Lang = "en" | "hi-deva" | "hi-latin";

/**
 * How offensive the *word* is, on a 1–4 scale. A property of the wordlist
 * entry, not of the match: `lund` is a 3 whether it was found exactly or by
 * dropping vowels.
 *
 * This measures offensiveness only. It says nothing about whether the match is
 * trustworthy — that is {@link Confidence} — and nothing about what you should
 * do about it, which is your decision, not this package's.
 *
 * - `4` — sexual/incest abuse, slurs: `bsdk`, `madarchod`, `chutiya`, `cunt`
 * - `3` — strong profanity: `fuck`, `lund`, `gandu`, `asshole`, `randi`
 * - `2` — crude but common: `shit`, `bitch`, `harami`, `crap`
 * - `1` — mild, tone only: `damn`, `bloody`, `saala`, `kamina`
 *
 * Ordered, so `severity >= 3` is a meaningful threshold.
 */
export type Severity = 1 | 2 | 3 | 4;

/**
 * How reliable *this particular match* is.
 *
 * - `high` — the term was found intact (exact, spaced-out, or leet-decoded).
 * - `low`  — either the term itself is ambiguous (`ambiguous: true` in the
 *            wordlist, e.g. `bc`, `laura`, `pussy`), or it was found by a lossy
 *            method (vowel-drop). Treat these as "needs review", not "profane".
 */
export type Confidence = "high" | "low";

/** Which strategy produced the match. Useful for debugging and for scoring. */
export type MatchMethod =
  /** Normalized token equals a wordlist term. */
  | "exact"
  /** Found in the separator-stripped string, e.g. `l u n d`, `f.u.c.k`. */
  | "spaced"
  /** Found after leet decoding, e.g. `ch00tiya`, `1und`. */
  | "leet"
  /** Multi-word wordlist entry, e.g. `bhen ke lode`, `piece of shit`. */
  | "phrase"
  /** Found after dropping vowels, e.g. `fck`. Opt-in; always `confidence: "low"`. */
  | "vowel-drop";

/** One piece of evidence. Never a verdict. */
export interface Match {
  /** The wordlist entry that matched, in its canonical form. */
  term: string;
  /** The substring of the original input that produced the match. */
  matchedText: string;
  /** Start index of `matchedText` in the original input. */
  index: number;
  /** End index (exclusive) of `matchedText` in the original input. */
  endIndex: number;
  severity: Severity;
  confidence: Confidence;
  method: MatchMethod;
  lang: Lang;
}

/** Result of {@link detect}. Evidence plus a score. Never a boolean. */
export interface DetectResult {
  /**
   * Sum of per-match weights. Higher means more/worse matches.
   *
   * This is NOT a probability and NOT a confidence. It is arithmetic over the
   * matches below, exposed so consumers can threshold on one number if they
   * want to. There is no default threshold; the package never decides.
   */
  score: number;
  /** Every match found, in order of appearance. Empty means nothing matched. */
  matches: Match[];
  /** The fully normalized form of the input. Exposed for debugging. */
  cleaned: string;
}

/** A single wordlist entry, as stored in `src/wordlists/*.json`. */
export interface WordlistEntry {
  /** Canonical spelling. Must survive the normalizer unchanged. */
  term: string;
  severity: Severity;
  /**
   * Only match when the term is a whole token, never inside a longer word.
   * Required for short stems: `ass` in `assignment`, `gand` in `Gandhi`.
   */
  wholeTokenOnly?: boolean;
  /**
   * The term collides with innocent usage (`bc` = before Christ, `laura` = a
   * name). Matches on it are reported with `confidence: "low"`.
   */
  ambiguous?: boolean;
  /** Why this entry is tricky. Documentation only; never used at runtime. */
  note?: string;
}

/** Shape of one `src/wordlists/*.json` file. */
export interface Wordlist {
  lang: Lang;
  terms: WordlistEntry[];
  /** Multi-word entries, matched across token boundaries. */
  phrases?: WordlistEntry[];
  note?: string;
}

/** Options for {@link detect}. Every one of them is off or empty by default. */
export interface DetectOptions {
  /**
   * Restrict which wordlists are consulted. Default: all three.
   * Narrowing this is the cheapest way to cut false positives.
   */
  langs?: Lang[];
  /**
   * Phrases that suppress any match overlapping them. Matched against the
   * normalized text, so pass `"dr lund"`, not `"Dr. Lund"`.
   *
   * Entries are phrases rather than bare words on purpose: allowlisting the
   * single word `lund` would also suppress real abuse. Allowlist the name,
   * not the word.
   */
  allowlist?: string[];
  /** Extra terms to match, merged with the built-in lists. */
  extraWords?: WordlistEntry[];
  /**
   * Also try matching with vowels removed (`fck` → `fuck`).
   *
   * Off by default, and it should usually stay off: `land` and `laundry`
   * collapse onto `lund`. Every match it produces is `confidence: "low"`.
   */
  vowelDrop?: boolean;
  /**
   * Per-severity weights used to compute `score`.
   * Default: `{ 1: 1, 2: 2, 3: 4, 4: 8 }`.
   *
   * A `confidence: "low"` match contributes half its weight, because a match on
   * an ambiguous term or via vowel-drop is evidence of a possible problem, not
   * of a definite one.
   */
  weights?: Partial<Record<Severity, number>>;
}
