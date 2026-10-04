/**
 * The normalizer.
 *
 * Every step transforms the text AND carries an offset map, so a match found in
 * the cleaned text can be reported with indices into the original input. See
 * docs/pipeline.md for why that map is not optional.
 *
 * Each step is exported so it can be tested on its own.
 */

/** A single character plus the index it came from in the original input. */
interface Cell {
  c: string;
  /** UTF-16 index in the ORIGINAL input string. */
  src: number;
}

/** Marker for a `*` that stood between two letters: `l*nd`. Matcher treats it as one unknown char. */
export const WILDCARD = "\u0001";

export interface Token {
  /** Normalized text of the token. */
  text: string;
  /** Start index in the original input. */
  start: number;
  /** End index (exclusive) in the original input. */
  end: number;
}

export interface NormalizeResult {
  /**
   * NFC-composed original text, for matching hi-deva terms BEFORE
   * transliteration. `लंड` must never become `land` before we look for it.
   */
  deva: string;
  /** Offset map for `deva`: devaMap[i] = index in original input. */
  devaMap: number[];
  /** Fully normalized text with all separators removed. */
  cleaned: string;
  /** Offset map for `cleaned`: cleanedMap[i] = index in original input. */
  cleanedMap: number[];
  /** Normalized tokens with original offsets, for whole-token and phrase matching. */
  tokens: Token[];
}

/* ------------------------------------------------------------------ *
 * Cell plumbing
 * ------------------------------------------------------------------ */

/** Split into cells by code point, so surrogate pairs (emoji) stay intact. */
function toCells(input: string): Cell[] {
  const out: Cell[] = [];
  let i = 0;
  for (const ch of input) {
    out.push({ c: ch, src: i });
    i += ch.length;
  }
  return out;
}

/**
 * Replace each cell with zero or more characters, all inheriting that cell's
 * source index. This is what keeps the offset map correct through steps that
 * change length.
 */
function mapCells(cells: Cell[], fn: (cell: Cell, i: number, all: Cell[]) => string): Cell[] {
  const out: Cell[] = [];
  for (let i = 0; i < cells.length; i++) {
    const cell = cells[i]!;
    for (const ch of fn(cell, i, cells)) out.push({ c: ch, src: cell.src });
  }
  return out;
}

function render(cells: Cell[]): { text: string; map: number[] } {
  let text = "";
  const map: number[] = [];
  for (const cell of cells) {
    text += cell.c;
    for (let k = 0; k < cell.c.length; k++) map.push(cell.src);
  }
  return { text, map };
}

/* ------------------------------------------------------------------ *
 * Step 1 — lowercase
 * ------------------------------------------------------------------ */

export function lowercase(cells: Cell[]): Cell[] {
  return mapCells(cells, (cell) => cell.c.toLowerCase());
}

/* ------------------------------------------------------------------ *
 * Step 2 — NFKD + strip LATIN combining marks only
 * ------------------------------------------------------------------ */

const DEVANAGARI = /[ऀ-ॿ]/;
const LATIN_MARKS = /[̀-ͯ]/g;

/**
 * Strips accents from Latin text (`café` → `cafe`) while leaving Devanagari
 * untouched.
 *
 * A generic `\p{Mn}` strip would destroy Devanagari matras and anusvara, which
 * collapses `लैंड` (land) and `लंड` (slur) onto the same string. See corpus
 * neg-043.
 */
export function stripLatinMarks(cells: Cell[]): Cell[] {
  return mapCells(cells, (cell) => {
    if (DEVANAGARI.test(cell.c)) return cell.c.normalize("NFC");
    return cell.c.normalize("NFKD").replace(LATIN_MARKS, "");
  });
}

/* ------------------------------------------------------------------ *
 * Step 3 — Devanagari → Latin transliteration
 * ------------------------------------------------------------------ */

const CONSONANTS: Record<string, string> = {
  क: "k", ख: "kh", ग: "g", घ: "gh", ङ: "ng",
  च: "ch", छ: "chh", ज: "j", झ: "jh", ञ: "ny",
  ट: "t", ठ: "th", ड: "d", ढ: "dh", ण: "n",
  त: "t", थ: "th", द: "d", ध: "dh", न: "n",
  प: "p", फ: "ph", ब: "b", भ: "bh", म: "m",
  य: "y", र: "r", ल: "l", व: "v",
  श: "sh", ष: "sh", स: "s", ह: "h",
  // nukta forms: transliterated the way Hinglish actually spells them
  ड़: "d", ढ़: "dh", ज़: "z", फ़: "f", ग़: "g", क़: "k",
};

const VOWELS: Record<string, string> = {
  अ: "a", आ: "a", इ: "i", ई: "i", उ: "u", ऊ: "u",
  ए: "e", ऐ: "ai", ओ: "o", औ: "au", ऋ: "ri", ऑ: "o",
};

/** Dependent vowel signs (matras) that replace a consonant's inherent `a`. */
const MATRAS: Record<string, string> = {
  "ा": "a",  // ा
  "ि": "i",  // ि
  "ी": "i",  // ी
  "ु": "u",  // ु
  "ू": "u",  // ू
  "ृ": "ri", // ृ
  "े": "e",  // े
  "ै": "ai", // ै
  "ो": "o",  // ो
  "ौ": "au", // ौ
  "ॉ": "o",  // ॉ
  "ॅ": "e",  // ॅ
};

const VIRAMA = "्";       // ् — kills the inherent vowel
const ANUSVARA = "ं";     // ं
const CHANDRABINDU = "ँ"; // ँ
const VISARGA = "ः";      // ः
const NUKTA = "़";        // ़

/**
 * Transliterates Devanagari to Latin so Devanagari input can also be checked
 * against the Latin wordlists.
 *
 * Deliberately approximate. Hindi deletes the inherent `a` in places this does
 * not model (`भोसड़ीके` comes out `bhosadike`, not `bhosdike`), so Hindi terms
 * are matched against the raw Devanagari first and this pass is the fallback.
 * Improving the schwa-deletion heuristic is a later task, not a blocker.
 */
export function transliterate(cells: Cell[]): Cell[] {
  const out: Cell[] = [];
  const push = (text: string, src: number): void => {
    for (const ch of text) out.push({ c: ch, src });
  };

  for (let i = 0; i < cells.length; i++) {
    const cell = cells[i]!;
    const ch = cell.c;

    if (ch === NUKTA) continue; // handled by the precomposed table, or dropped

    const cons = CONSONANTS[ch];
    if (cons !== undefined) {
      push(cons, cell.src);

      // Look ahead: a matra or virama replaces the inherent `a`.
      const next = cells[i + 1];
      const nextCh = next?.c ?? "";
      if (nextCh === VIRAMA) {
        i += 1; // inherent vowel killed, emit nothing
      } else if (MATRAS[nextCh] !== undefined) {
        push(MATRAS[nextCh]!, next!.src);
        i += 1;
      } else {
        // Inherent `a`, except word-finally (Hindi drops it: राम → ram).
        const atWordEnd = next === undefined || !DEVANAGARI.test(nextCh);
        if (!atWordEnd) push("a", cell.src);
      }
      continue;
    }

    const vowel = VOWELS[ch];
    if (vowel !== undefined) {
      push(vowel, cell.src);
      continue;
    }

    if (ch === ANUSVARA || ch === CHANDRABINDU) {
      push("n", cell.src);
      continue;
    }
    if (ch === VISARGA) {
      push("h", cell.src);
      continue;
    }
    if (MATRAS[ch] !== undefined) {
      // Orphan matra (no preceding consonant) — keep its vowel.
      push(MATRAS[ch]!, cell.src);
      continue;
    }

    out.push(cell); // not Devanagari, pass through untouched
  }

  return out;
}

/* ------------------------------------------------------------------ *
 * Step 4 — collapse repeated characters
 * ------------------------------------------------------------------ */

/** A run must reach this length before it collapses. See the note below. */
const MIN_RUN = 3;

/**
 * `fuuuuck` → `fuck`, `LUNDDD` → `lund`, `Uselessss` → `useles`.
 *
 * Only runs of **three or more** identical characters collapse. Doubled letters
 * are left alone, which is deliberate and was forced by the corpus:
 *
 * - `chot` (injury) vs `choot` (vulgar) differ only in vowel length. Collapsing
 *   doubles makes them the same string (neg-069).
 * - `chhod` (to leave) vs `chod` (vulgar) differ only in a doubled `h`
 *   (neg-073).
 *
 * Hinglish uses doubled letters to carry real distinctions, so collapsing them
 * destroys meaning. The cost is that exactly-doubled obfuscation (`fuuck`) is
 * missed; three or more is the common form and is still caught.
 */
/** A token must stay at least this long for a trailing double to collapse. */
const MIN_COLLAPSED_TOKEN = 4;

export function collapseRepeats(cells: Cell[]): Cell[] {
  const out: Cell[] = [];
  const isWordChar = /\p{L}|\p{N}/u;
  let tokenLen = 0; // characters emitted in the current token so far
  let i = 0;

  while (i < cells.length) {
    const cell = cells[i]!;
    let run = 1;
    while (i + run < cells.length && cells[i + run]!.c === cell.c) run++;

    if (!isWordChar.test(cell.c)) {
      // separator: ends the current token
      for (let k = 0; k < run; k++) out.push(cells[i + k]!);
      tokenLen = 0;
      i += run;
      continue;
    }

    const after = cells[i + run]?.c;
    const atTokenEnd = after === undefined || !isWordChar.test(after);

    // A doubled letter at the END of a token is usually obfuscation (`lundd`,
    // `chutiyaa`) — but only when enough token remains. Collapsing short tokens
    // is what turned `ass` into `as` and matched the English word "as"; and
    // Hindi's meaningful doubles sit mid-word (`chhod`, `choot`), which is why
    // run-of-two never collapses there.
    const collapse =
      run >= MIN_RUN || (run === 2 && atTokenEnd && tokenLen + 1 >= MIN_COLLAPSED_TOKEN);

    if (collapse) {
      out.push(cell);
      tokenLen += 1;
    } else {
      for (let k = 0; k < run; k++) out.push(cells[i + k]!);
      tokenLen += run;
    }

    i += run;
  }

  return out;
}

/* ------------------------------------------------------------------ *
 * Step 5 — leetspeak decoding
 * ------------------------------------------------------------------ */

/**
 * Digits and symbols standing in for letters.
 *
 * `1`, `l` and `i` all fold onto `i`: `1` is used for both `i` and `l`, and a
 * one-to-one map cannot represent that. Folding the whole class is what lets
 * `1und` reach `lund` without generating every spelling variant. The cost is a
 * slightly coarser alphabet; `wholeTokenOnly` carries the weight of keeping
 * that safe.
 */
const LEET: Record<string, string> = {
  "0": "o", "3": "e", "4": "a", "5": "s", "6": "g", "7": "t", "8": "b", "9": "g",
  "@": "a", $: "s", "+": "t", "(": "c", "€": "e", "£": "e", "¢": "c",
  "1": "i", l: "i", i: "i", "|": "i",
  // `!` is NOT here. It reads as `i` in theory, but in real reviews it is
  // punctuation: `lund!!!` must still tokenize as `lund` (pos-014), and
  // `Uselessss!!!!` must not grow letters (neg-007).
};

export function decodeLeet(cells: Cell[]): Cell[] {
  return mapCells(cells, (cell) => LEET[cell.c] ?? cell.c);
}

/* ------------------------------------------------------------------ *
 * Step 6 — separators
 * ------------------------------------------------------------------ */

const KEEP = /\p{L}|\p{N}/u;
const ZERO_WIDTH = /[​-‍﻿­]/;

/** True for anything that is not a letter or digit: spaces, punctuation, emoji, zero-width. */
function isSeparator(ch: string): boolean {
  if (ch === WILDCARD) return false;
  if (ZERO_WIDTH.test(ch)) return true;
  return !KEEP.test(ch);
}

/**
 * Characters used to mask a single letter: `l*nd`, `lu#d`.
 *
 * Deliberately narrow. `.`, `_`, `-` and whitespace are NOT here: those are
 * spacing obfuscation (`l.u.n.d`), where the character stands for nothing
 * rather than for a hidden letter. Treating both the same way is impossible —
 * one deletes, the other substitutes.
 */
const MASK_CHARS = new Set(["*", "#", "%", "&", "!"]);

/**
 * Turns a masking character between two letters into {@link WILDCARD}, and
 * drops it elsewhere.
 *
 * `l*nd` is a masked slur; `L***` is a review we masked ourselves upstream and
 * must pass (corpus neg-023), which works because the trailing `*`s have no
 * letter after them.
 */
export function markWildcards(cells: Cell[]): Cell[] {
  return mapCells(cells, (cell, i, all) => {
    if (!MASK_CHARS.has(cell.c)) return cell.c;
    const before = all[i - 1]?.c ?? "";
    const after = all[i + 1]?.c ?? "";
    const flanked = KEEP.test(before) && KEEP.test(after);
    return flanked ? WILDCARD : "";
  });
}

export function stripSeparators(cells: Cell[]): Cell[] {
  return cells.filter((cell) => !isSeparator(cell.c));
}

/* ------------------------------------------------------------------ *
 * Tokenization
 * ------------------------------------------------------------------ */

/** Splits on separators, keeping each token's offsets in the original input. */
export function tokenize(cells: Cell[]): Token[] {
  const tokens: Token[] = [];
  let current: Cell[] = [];

  const flush = (): void => {
    if (current.length === 0) return;
    const first = current[0]!;
    const last = current[current.length - 1]!;
    tokens.push({
      text: current.map((cell) => cell.c).join(""),
      start: first.src,
      end: last.src + last.c.length,
    });
    current = [];
  };

  for (const cell of cells) {
    if (isSeparator(cell.c)) flush();
    else current.push(cell);
  }
  flush();
  return tokens;
}

/* ------------------------------------------------------------------ *
 * The pipeline
 * ------------------------------------------------------------------ */

/**
 * Runs the full pipeline.
 *
 * @throws TypeError if `input` is not a string. Rating-only reviews have no
 * text; filter those out before calling (corpus err-001).
 */
export function normalize(input: string): NormalizeResult {
  if (typeof input !== "string") {
    throw new TypeError(`hinglish-profanity: expected a string, received ${typeof input}`);
  }

  // Devanagari pass: lowercase + NFC only. No transliteration, no collapsing.
  const devaCells = stripLatinMarks(lowercase(toCells(input)));
  const deva = render(devaCells);

  // Latin pass: the full pipeline.
  let cells = devaCells;
  cells = transliterate(cells);
  cells = collapseRepeats(cells);
  cells = decodeLeet(cells);
  cells = markWildcards(cells);

  const tokens = tokenize(cells);
  const cleaned = render(stripSeparators(cells));

  return {
    deva: deva.text,
    devaMap: deva.map,
    cleaned: cleaned.text,
    cleanedMap: cleaned.map,
    tokens,
  };
}

/**
 * Normalizes a wordlist term the same way as input, so both sides of a
 * comparison are in the same alphabet.
 *
 * Without this, `choot` in the wordlist never matches anything, because input
 * `choot` collapses to `chot`.
 */
export function normalizeTerm(term: string): string {
  return normalize(term).cleaned;
}
