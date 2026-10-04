/**
 * The matcher.
 *
 * Takes normalized text (plus its offset maps) and a prepared wordlist, and
 * produces evidence. It makes no decisions: no thresholds, no blocking, no
 * boolean. Scoring lives in index.ts; this file only finds things.
 */

import type {
  DetectOptions,
  Lang,
  Match,
  MatchMethod,
  Severity,
  Wordlist,
  WordlistEntry,
} from "./types";
import { normalizeTerm, WILDCARD, type NormalizeResult, type Token } from "./normalize";

import enList from "./wordlists/en.json";
import hiDevaList from "./wordlists/hi-deva.json";
import hiLatinList from "./wordlists/hi-latin.json";

/** A wordlist entry with its normalized form precomputed. */
interface PreparedEntry {
  /** Canonical term as written in the wordlist (reported as `Match.term`). */
  term: string;
  /** Normalized form, compared against normalized input. */
  norm: string;
  /** For phrases: the normalized form split into tokens. */
  parts: string[];
  severity: Severity;
  wholeTokenOnly: boolean;
  ambiguous: boolean;
  lang: Lang;
  isPhrase: boolean;
}

export interface PreparedWordlists {
  /** Latin-alphabet entries, matched against normalized text. */
  latin: PreparedEntry[];
  /** Devanagari entries, matched against near-raw NFC text before transliteration. */
  deva: PreparedEntry[];
}

const WORDLISTS: Wordlist[] = [
  enList as Wordlist,
  hiLatinList as Wordlist,
  hiDevaList as Wordlist,
];

function prepareEntry(entry: WordlistEntry, lang: Lang, isPhrase: boolean): PreparedEntry {
  // Devanagari terms are matched pre-transliteration, so they only get the
  // same light touch the Devanagari pass applies: lowercase + NFC.
  const norm =
    lang === "hi-deva" ? entry.term.toLowerCase().normalize("NFC") : normalizeTerm(entry.term);

  return {
    term: entry.term,
    norm,
    parts: isPhrase ? norm.split(/\s+/).filter((p) => p.length > 0) : [norm],
    severity: entry.severity,
    wholeTokenOnly: entry.wholeTokenOnly === true,
    ambiguous: entry.ambiguous === true,
    lang,
    isPhrase,
  };
}

/**
 * Normalizes every wordlist entry once, so matching is string comparison.
 *
 * Phrases are normalized per-word: `bhen ke lode` keeps its three parts, since
 * the token matcher walks token sequences.
 */
export function prepareWordlists(options: DetectOptions = {}): PreparedWordlists {
  const langs = options.langs;
  const latin: PreparedEntry[] = [];
  const deva: PreparedEntry[] = [];

  for (const list of WORDLISTS) {
    if (langs !== undefined && !langs.includes(list.lang)) continue;

    for (const entry of list.terms) {
      const prepared = prepareEntry(entry, list.lang, false);
      (list.lang === "hi-deva" ? deva : latin).push(prepared);
    }
    for (const entry of list.phrases ?? []) {
      // A phrase is multi-word; normalizeTerm strips the spaces, so phrases are
      // normalized word by word instead.
      const parts = entry.term
        .split(/\s+/)
        .map((w) => (list.lang === "hi-deva" ? w.toLowerCase().normalize("NFC") : normalizeTerm(w)))
        .filter((w) => w.length > 0);

      const prepared: PreparedEntry = {
        term: entry.term,
        norm: parts.join(" "),
        parts,
        severity: entry.severity,
        wholeTokenOnly: entry.wholeTokenOnly === true,
        ambiguous: entry.ambiguous === true,
        lang: list.lang,
        isPhrase: true,
      };
      (list.lang === "hi-deva" ? deva : latin).push(prepared);
    }
  }

  for (const entry of options.extraWords ?? []) {
    const lang: Lang = /[ऀ-ॿ]/.test(entry.term) ? "hi-deva" : "en";
    const isPhrase = /\s/.test(entry.term.trim());
    const prepared = prepareEntry(entry, lang, isPhrase);
    if (isPhrase) prepared.parts = prepared.norm.split(/\s+/).filter((p) => p.length > 0);
    (lang === "hi-deva" ? deva : latin).push(prepared);
  }

  return { latin, deva };
}

/* ------------------------------------------------------------------ *
 * Wildcard-aware search
 * ------------------------------------------------------------------ */

/** True if `haystack` matches `needle` at `at`, treating WILDCARD as any one char. */
function matchesAt(haystack: string, needle: string, at: number): boolean {
  if (at + needle.length > haystack.length) return false;
  for (let i = 0; i < needle.length; i++) {
    const h = haystack[at + i]!;
    if (h === WILDCARD) continue; // `l*nd`: the masked character matches anything
    if (h !== needle[i]!) return false;
  }
  return true;
}

/** Every start index at which `needle` occurs in `haystack`. */
function findAll(haystack: string, needle: string): number[] {
  const hits: number[] = [];
  if (needle.length === 0) return hits;
  for (let i = 0; i + needle.length <= haystack.length; i++) {
    if (matchesAt(haystack, needle, i)) hits.push(i);
  }
  return hits;
}

/* ------------------------------------------------------------------ *
 * Method / confidence
 * ------------------------------------------------------------------ */

/**
 * Describes how the input differed from the canonical term, for `Match.method`.
 *
 * Normalization is lossy by design, so this is inferred from the raw slice:
 * identical text is `exact`, text carrying digits/symbols/wildcards is `leet`,
 * text containing separators is `spaced`.
 */
function methodFor(rawSlice: string, term: string, fallback: MatchMethod): MatchMethod {
  const raw = rawSlice.toLowerCase();
  if (raw === term.toLowerCase()) return fallback === "phrase" ? "phrase" : "exact";
  if (/[\s.\-_*|!]/.test(rawSlice)) return fallback === "phrase" ? "phrase" : "spaced";
  if (/[0-9@$+(€£¢]/.test(rawSlice)) return "leet";
  return fallback;
}

function confidenceFor(entry: PreparedEntry, method: MatchMethod): "high" | "low" {
  return entry.ambiguous || method === "vowel-drop" ? "low" : "high";
}

function makeMatch(
  input: string,
  entry: PreparedEntry,
  start: number,
  end: number,
  fallback: MatchMethod,
): Match {
  const matchedText = input.slice(start, end);
  const method = fallback === "vowel-drop" ? "vowel-drop" : methodFor(matchedText, entry.term, fallback);
  return {
    term: entry.term,
    matchedText,
    index: start,
    endIndex: end,
    severity: entry.severity,
    confidence: confidenceFor(entry, method),
    method,
    lang: entry.lang,
  };
}

/* ------------------------------------------------------------------ *
 * The individual matchers
 * ------------------------------------------------------------------ */

/** Token equality: the strictest and most reliable pass. */
function matchTokens(input: string, tokens: Token[], entries: PreparedEntry[]): Match[] {
  const out: Match[] = [];
  for (const token of tokens) {
    for (const entry of entries) {
      if (entry.isPhrase) continue;
      if (matchesAt(token.text, entry.norm, 0) && token.text.length === entry.norm.length) {
        out.push(makeMatch(input, entry, token.start, token.end, "exact"));
      }
    }
  }
  return out;
}

/** Longest run of tokens a spaced-out term may span: `l . u . n . d` is 4. */
const MAX_RUN_TOKENS = 8;

/**
 * Shortest term allowed to match across token boundaries.
 *
 * Without this, `b.a.k.w.a.s` reports `ass`: its trailing `a` and `s` tokens
 * concatenate to `as`, which is what `ass` normalizes to (neg-062). Two- and
 * three-letter terms spread across tokens are noise, not obfuscation.
 */
const MIN_RUN_TERM_LEN = 4;

/**
 * Concatenations of consecutive tokens: catches `l u n d`, `l.u.n.d`,
 * `l_u_n_d`, `lu<zero-width>nd`.
 *
 * This is how `wholeTokenOnly` terms get their spaced variants. The run has
 * token boundaries at both ends, so `lund` spelled across four tokens matches,
 * while `ass` inside the single token `assignment` does not — which is exactly
 * the distinction the stripped-string scan cannot make.
 */
function matchTokenRuns(input: string, tokens: Token[], entries: PreparedEntry[]): Match[] {
  const out: Match[] = [];

  for (let i = 0; i < tokens.length; i++) {
    let text = tokens[i]!.text;

    for (let n = 1; n < MAX_RUN_TOKENS && i + n < tokens.length; n++) {
      text += tokens[i + n]!.text;
      const last = tokens[i + n]!;

      for (const entry of entries) {
        if (entry.isPhrase) continue;
        if (entry.norm.length < MIN_RUN_TERM_LEN) continue;
        if (entry.norm.length !== text.length) continue;
        if (!matchesAt(text, entry.norm, 0)) continue;
        out.push(makeMatch(input, entry, tokens[i]!.start, last.end, "spaced"));
      }
    }
  }

  return out;
}

/** Multi-word entries: slide the phrase's parts over the token array. */
function matchPhrases(input: string, tokens: Token[], entries: PreparedEntry[]): Match[] {
  const out: Match[] = [];
  for (const entry of entries) {
    if (!entry.isPhrase || entry.parts.length === 0) continue;
    for (let i = 0; i + entry.parts.length <= tokens.length; i++) {
      let hit = true;
      for (let k = 0; k < entry.parts.length; k++) {
        if (tokens[i + k]!.text !== entry.parts[k]!) {
          hit = false;
          break;
        }
      }
      if (hit) {
        const first = tokens[i]!;
        const last = tokens[i + entry.parts.length - 1]!;
        out.push(makeMatch(input, entry, first.start, last.end, "phrase"));
      }
    }
  }
  return out;
}

/**
 * Substring scan over the separator-stripped text: catches `l u n d`, `f.u.c.k`.
 *
 * Skips `wholeTokenOnly` entries — this is the pass that would otherwise find
 * `ass` inside `assignment` and `gand` inside `Gandhi`.
 */
function matchStripped(
  input: string,
  cleaned: string,
  cleanedMap: number[],
  entries: PreparedEntry[],
): Match[] {
  const out: Match[] = [];
  for (const entry of entries) {
    if (entry.wholeTokenOnly || entry.isPhrase) continue;
    for (const at of findAll(cleaned, entry.norm)) {
      const start = cleanedMap[at];
      const lastCharStart = cleanedMap[at + entry.norm.length - 1];
      if (start === undefined || lastCharStart === undefined) continue;
      // +1 because the map stores the start of the original character.
      out.push(makeMatch(input, entry, start, lastCharStart + 1, "spaced"));
    }
  }
  return out;
}

const DEVA_LETTER = /[ऀ-ॿ]/;

/**
 * Devanagari pass, run against NFC text before transliteration.
 *
 * `लंड` must be found here; after transliteration it becomes `land`, which both
 * loses the slur and collides with the English word.
 */
function matchDevanagari(
  input: string,
  deva: string,
  devaMap: number[],
  entries: PreparedEntry[],
): Match[] {
  const out: Match[] = [];
  for (const entry of entries) {
    for (const at of findAll(deva, entry.norm)) {
      if (entry.wholeTokenOnly) {
        const before = at > 0 ? deva[at - 1]! : "";
        const after = deva[at + entry.norm.length] ?? "";
        if (DEVA_LETTER.test(before) || DEVA_LETTER.test(after)) continue;
      }
      const start = devaMap[at];
      const lastCharStart = devaMap[at + entry.norm.length - 1];
      if (start === undefined || lastCharStart === undefined) continue;
      out.push(makeMatch(input, entry, start, lastCharStart + 1, "exact"));
    }
  }
  return out;
}

const VOWELS = /[aeiou]/g;

/**
 * Opt-in vowel-drop pass. Whole tokens only, never substrings.
 *
 * `fck` → `fuck`, but also `land` → `lnd` → `lund` and `laundry` → `lndry`.
 * Always `confidence: "low"`; off unless the consumer asks for it.
 */
function matchVowelDrop(input: string, tokens: Token[], entries: PreparedEntry[]): Match[] {
  const out: Match[] = [];
  for (const token of tokens) {
    const bare = token.text.replace(VOWELS, "");
    if (bare.length < 3) continue; // too short to be evidence of anything
    for (const entry of entries) {
      if (entry.isPhrase) continue;
      const entryBare = entry.norm.replace(VOWELS, "");
      if (entryBare.length < 3) continue;
      if (bare === entryBare && token.text !== entry.norm) {
        out.push(makeMatch(input, entry, token.start, token.end, "vowel-drop"));
      }
    }
  }
  return out;
}

/* ------------------------------------------------------------------ *
 * Allowlist + dedupe
 * ------------------------------------------------------------------ */

/** Original-text ranges covered by allowlist phrases. */
function allowedRanges(
  cleaned: string,
  cleanedMap: number[],
  allowlist: string[],
): Array<[number, number]> {
  const ranges: Array<[number, number]> = [];
  for (const phrase of allowlist) {
    const norm = normalizeTerm(phrase);
    if (norm.length === 0) continue;
    for (const at of findAll(cleaned, norm)) {
      const start = cleanedMap[at];
      const lastCharStart = cleanedMap[at + norm.length - 1];
      if (start === undefined || lastCharStart === undefined) continue;
      ranges.push([start, lastCharStart + 1]);
    }
  }
  return ranges;
}

function overlaps(match: Match, ranges: Array<[number, number]>): boolean {
  return ranges.some(([start, end]) => match.index < end && start < match.endIndex);
}

/**
 * Drops duplicates and matches swallowed by a longer one.
 *
 * `motherfucker` would otherwise also report `fuck`, and `piece of shit` would
 * also report `shit`. The longest match at a position wins.
 */
function dedupe(matches: Match[]): Match[] {
  const sorted = [...matches].sort(
    (a, b) => a.index - b.index || b.endIndex - b.index - (a.endIndex - a.index),
  );

  const kept: Match[] = [];
  for (const match of sorted) {
    const swallowed = kept.some(
      (k) => k.index <= match.index && match.endIndex <= k.endIndex && k.term !== match.term,
    );
    if (swallowed) continue;
    const duplicate = kept.some(
      (k) => k.term === match.term && k.index === match.index && k.endIndex === match.endIndex,
    );
    if (duplicate) continue;
    kept.push(match);
  }
  return kept.sort((a, b) => a.index - b.index);
}

/* ------------------------------------------------------------------ *
 * Entry point
 * ------------------------------------------------------------------ */

/** Runs every enabled matcher, applies the allowlist, and returns ordered evidence. */
export function findMatches(
  input: string,
  normalized: NormalizeResult,
  lists: PreparedWordlists,
  options: DetectOptions = {},
): Match[] {
  const { tokens, cleaned, cleanedMap, deva, devaMap } = normalized;

  const found: Match[] = [
    ...matchDevanagari(input, deva, devaMap, lists.deva),
    ...matchTokens(input, tokens, lists.latin),
    ...matchTokenRuns(input, tokens, lists.latin),
    ...matchPhrases(input, tokens, lists.latin),
    ...matchStripped(input, cleaned, cleanedMap, lists.latin),
  ];

  if (options.vowelDrop === true) {
    found.push(...matchVowelDrop(input, tokens, lists.latin));
  }

  const allowlist = options.allowlist ?? [];
  const ranges = allowlist.length > 0 ? allowedRanges(cleaned, cleanedMap, allowlist) : [];
  const surviving = ranges.length > 0 ? found.filter((m) => !overlaps(m, ranges)) : found;

  return dedupe(surviving);
}
