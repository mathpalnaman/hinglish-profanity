/**
 * hinglish-profanity — profanity detection for English, Hindi (Devanagari) and
 * romanized Hinglish.
 *
 * The package reports evidence. It never decides. There is no default
 * threshold, no default allowlist, and no `isProfane` boolean, because what
 * counts as actionable depends on your product, not on this library.
 *
 * @example
 * ```ts
 * import { detect } from "hinglish-profanity";
 *
 * const result = detect(review.text);
 *
 * // Your policy, not the package's:
 * if (result.matches.some(m => m.severity >= 3 && m.confidence === "high")) {
 *   block();
 * } else if (result.matches.length > 0) {
 *   queueForReview();
 * }
 * ```
 */

import type { DetectOptions, DetectResult, Match, Severity } from "./types";
import { normalize } from "./normalize";
import { findMatches, prepareWordlists, type PreparedWordlists } from "./match";

export type {
  Confidence,
  DetectOptions,
  DetectResult,
  Lang,
  Match,
  MatchMethod,
  Severity,
  Wordlist,
  WordlistEntry,
} from "./types";

export { normalize, normalizeTerm } from "./normalize";

declare const __VERSION__: string;
declare const __WORDLIST_HASH__: string;

/** Package version, identical to the Java artifact built from the same tag. */
export const VERSION: string = __VERSION__;

/**
 * First 12 hex chars of SHA-256 over the three wordlist files. Two builds with
 * the same hash match the same terms, whatever language they are written in.
 */
export const WORDLIST_HASH: string = __WORDLIST_HASH__;

/** Default score weights. Doubling per level: one level-4 hit outranks any pile of level-1s. */
export const DEFAULT_WEIGHTS: Record<Severity, number> = { 1: 1, 2: 2, 3: 4, 4: 8 };

/**
 * Cached prepared wordlists for the common case (no `langs`, no `extraWords`).
 *
 * Preparing means normalizing ~110 terms, which is wasted work on every call.
 * Any call that narrows `langs` or adds `extraWords` prepares its own lists and
 * is not cached, so a consumer passing a fresh `extraWords` array per call pays
 * for it — documented rather than silently cached on object identity.
 */
let defaultLists: PreparedWordlists | undefined;

function listsFor(options: DetectOptions): PreparedWordlists {
  const needsCustom =
    (options.langs !== undefined && options.langs.length > 0) ||
    (options.extraWords !== undefined && options.extraWords.length > 0);

  if (needsCustom) return prepareWordlists(options);

  defaultLists ??= prepareWordlists();
  return defaultLists;
}

/**
 * Sums match weights. A `confidence: "low"` match counts half, because an
 * ambiguous term or a vowel-dropped hit is evidence of a possible problem, not
 * of a definite one.
 */
export function scoreOf(matches: Match[], weights?: DetectOptions["weights"]): number {
  let score = 0;
  for (const match of matches) {
    const weight = weights?.[match.severity] ?? DEFAULT_WEIGHTS[match.severity];
    score += match.confidence === "low" ? weight / 2 : weight;
  }
  // Two decimal places: halved weights can produce .5, and float drift is ugly
  // in a value consumers may log or store.
  return Math.round(score * 100) / 100;
}

/**
 * Scans `input` for profanity and returns every match found, with positions
 * into the original string.
 *
 * @param input Raw text. A string, deliberately: the package has no opinion
 * about your data shape, so pass whichever field you want checked.
 * @param options All off or empty by default.
 * @returns Evidence: `score`, `matches`, and the normalized `cleaned` text.
 * @throws TypeError if `input` is not a string — including `null`, which is
 * what a rating-only review usually is. Filter those out before calling.
 */
export function detect(input: string, options: DetectOptions = {}): DetectResult {
  const normalized = normalize(input);
  const matches = findMatches(input, normalized, listsFor(options), options);

  return {
    score: scoreOf(matches, options.weights),
    matches,
    cleaned: normalized.cleaned,
  };
}
