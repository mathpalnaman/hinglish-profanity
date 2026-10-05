package io.github.mathpalnaman.hinglishprofanity;

import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The normalizer. A line-for-line port of {@code src/normalize.ts}.
 *
 * <p>Every step transforms the text AND carries an offset map, so a match found
 * in the cleaned text can be reported with indices into the original input.
 *
 * <p>The JS implementation is the source of truth. Where it does something
 * surprising, this class does the same thing on purpose; see KNOWN_ISSUES.md
 * before "fixing" anything here, because the parity test compares the two
 * implementations result for result.
 */
final class Normalizer {

  private Normalizer() {}

  /** Marker for a mask character that stood between two letters: {@code l*nd}. */
  static final char WILDCARD = '\u0001';

  /** A normalized token with its offsets in the original input. */
  record Token(String text, int start, int end) {}

  /** Everything the matcher needs. Arrays are owned by the caller; never shared. */
  record Normalized(String deva, int[] devaMap, String cleaned, int[] cleanedMap, List<Token> tokens) {}

  /* ------------------------------------------------------------------ *
   * Cell plumbing
   * ------------------------------------------------------------------ */

  /**
   * One code point per cell, plus the UTF-16 index it came from in the ORIGINAL
   * input. JS keeps an array of {c, src} objects; two parallel int arrays are
   * the same thing without the allocation.
   */
  private static final class Cells {
    int[] cp;
    int[] src;
    int size;

    Cells(int capacity) {
      cp = new int[Math.max(capacity, 8)];
      src = new int[Math.max(capacity, 8)];
    }

    void add(int codePoint, int source) {
      if (size == cp.length) {
        cp = java.util.Arrays.copyOf(cp, size * 2);
        src = java.util.Arrays.copyOf(src, size * 2);
      }
      cp[size] = codePoint;
      src[size] = source;
      size++;
    }

    /** Appends every code point of {@code text}, all inheriting {@code source}. */
    void addAll(String text, int source) {
      for (int i = 0; i < text.length(); ) {
        int codePoint = text.codePointAt(i);
        add(codePoint, source);
        i += Character.charCount(codePoint);
      }
    }
  }

  /**
   * Splits by code point, so surrogate pairs stay intact. A lone surrogate is a
   * cell of its own, exactly as JS {@code for...of} yields it.
   */
  private static Cells toCells(String input) {
    Cells out = new Cells(input.length());
    for (int i = 0; i < input.length(); ) {
      int codePoint = input.codePointAt(i);
      out.add(codePoint, i);
      i += Character.charCount(codePoint);
    }
    return out;
  }

  /* ------------------------------------------------------------------ *
   * Step 1 — lowercase
   * ------------------------------------------------------------------ */

  private static Cells lowercase(Cells cells) {
    Cells out = new Cells(cells.size);
    for (int i = 0; i < cells.size; i++) {
      int codePoint = cells.cp[i];
      if (codePoint < 0x80) {
        out.add(codePoint >= 'A' && codePoint <= 'Z' ? codePoint + 32 : codePoint, cells.src[i]);
      } else {
        // Per code point, as JS does: no cross-character context (final sigma),
        // and Locale.ROOT so a Turkish default locale cannot change the result.
        out.addAll(Character.toString(codePoint).toLowerCase(Locale.ROOT), cells.src[i]);
      }
    }
    return out;
  }

  /* ------------------------------------------------------------------ *
   * Step 2 — NFKD + strip LATIN combining marks only
   * ------------------------------------------------------------------ */

  static boolean isDevanagari(int codePoint) {
    return codePoint >= 0x0900 && codePoint <= 0x097F;
  }

  /**
   * Strips accents from Latin text while leaving Devanagari untouched. A
   * generic mark strip would collapse {@code लैंड} (land) and {@code लंड} (slur)
   * onto the same string.
   *
   * <p>Normalization is applied per code point, as in JS. One consequence: NFC
   * of a lone precomposed nukta letter (U+095C) DEcomposes it, because those
   * letters are composition exclusions. Both sides of the comparison go through
   * the same call, so matching still works.
   */
  private static Cells stripLatinMarks(Cells cells) {
    Cells out = new Cells(cells.size);
    for (int i = 0; i < cells.size; i++) {
      int codePoint = cells.cp[i];
      int source = cells.src[i];
      if (codePoint < 0x80) {
        out.add(codePoint, source);
      } else if (isDevanagari(codePoint)) {
        out.addAll(java.text.Normalizer.normalize(Character.toString(codePoint), Form.NFC), source);
      } else {
        String decomposed = java.text.Normalizer.normalize(Character.toString(codePoint), Form.NFKD);
        for (int k = 0; k < decomposed.length(); ) {
          int part = decomposed.codePointAt(k);
          if (part < 0x0300 || part > 0x036F) out.add(part, source);
          k += Character.charCount(part);
        }
      }
    }
    return out;
  }

  /* ------------------------------------------------------------------ *
   * Step 3 — Devanagari → Latin transliteration
   * ------------------------------------------------------------------ */

  private static final int VIRAMA = 0x094D;
  private static final int ANUSVARA = 0x0902;
  private static final int CHANDRABINDU = 0x0901;
  private static final int VISARGA = 0x0903;
  private static final int NUKTA = 0x093C;

  /** Indexed by (code point - 0x0900). */
  private static final String[] CONSONANTS = new String[0x80];
  private static final String[] VOWELS = new String[0x80];
  private static final String[] MATRAS = new String[0x80];

  static {
    String[][] consonants = {
      {"क", "k"}, {"ख", "kh"}, {"ग", "g"}, {"घ", "gh"}, {"ङ", "ng"},
      {"च", "ch"}, {"छ", "chh"}, {"ज", "j"}, {"झ", "jh"}, {"ञ", "ny"},
      {"ट", "t"}, {"ठ", "th"}, {"ड", "d"}, {"ढ", "dh"}, {"ण", "n"},
      {"त", "t"}, {"थ", "th"}, {"द", "d"}, {"ध", "dh"}, {"न", "n"},
      {"प", "p"}, {"फ", "ph"}, {"ब", "b"}, {"भ", "bh"}, {"म", "m"},
      {"य", "y"}, {"र", "r"}, {"ल", "l"}, {"व", "v"},
      {"श", "sh"}, {"ष", "sh"}, {"स", "s"}, {"ह", "h"},
      // The JS table also lists nukta forms (ड़ → d, ज़ → z, फ़ → f …) keyed by
      // two code points. A cell is one code point, so those keys never match
      // and are not ported. See KNOWN_ISSUES.md, "dead nukta table".
    };
    String[][] vowels = {
      {"अ", "a"}, {"आ", "a"}, {"इ", "i"}, {"ई", "i"}, {"उ", "u"},
      {"ऊ", "u"}, {"ए", "e"}, {"ऐ", "ai"}, {"ओ", "o"}, {"औ", "au"},
      {"ऋ", "ri"}, {"ऑ", "o"},
    };
    String[][] matras = {
      {"ा", "a"}, {"ि", "i"}, {"ी", "i"}, {"ु", "u"}, {"ू", "u"},
      {"ृ", "ri"}, {"े", "e"}, {"ै", "ai"}, {"ो", "o"}, {"ौ", "au"},
      {"ॉ", "o"}, {"ॅ", "e"},
    };
    for (String[] pair : consonants) CONSONANTS[pair[0].charAt(0) - 0x0900] = pair[1];
    for (String[] pair : vowels) VOWELS[pair[0].charAt(0) - 0x0900] = pair[1];
    for (String[] pair : matras) MATRAS[pair[0].charAt(0) - 0x0900] = pair[1];
  }

  private static String lookup(String[] table, int codePoint) {
    return isDevanagari(codePoint) ? table[codePoint - 0x0900] : null;
  }

  /**
   * Transliterates Devanagari to Latin so Devanagari input can also be checked
   * against the Latin wordlists. Deliberately approximate; Hindi terms are
   * matched against the raw Devanagari first and this pass is the fallback.
   */
  private static Cells transliterate(Cells cells) {
    Cells out = new Cells(cells.size + 8);

    for (int i = 0; i < cells.size; i++) {
      int codePoint = cells.cp[i];
      int source = cells.src[i];

      if (codePoint == NUKTA) continue;

      String consonant = lookup(CONSONANTS, codePoint);
      if (consonant != null) {
        out.addAll(consonant, source);

        // Look ahead: a matra or virama replaces the inherent `a`.
        boolean hasNext = i + 1 < cells.size;
        int next = hasNext ? cells.cp[i + 1] : -1;
        String matra = hasNext ? lookup(MATRAS, next) : null;
        if (hasNext && next == VIRAMA) {
          i += 1; // inherent vowel killed, emit nothing
        } else if (matra != null) {
          out.addAll(matra, cells.src[i + 1]);
          i += 1;
        } else {
          // Inherent `a`, except word-finally (Hindi drops it: राम → ram).
          boolean atWordEnd = !hasNext || !isDevanagari(next);
          if (!atWordEnd) out.add('a', source);
        }
        continue;
      }

      String vowel = lookup(VOWELS, codePoint);
      if (vowel != null) {
        out.addAll(vowel, source);
        continue;
      }

      if (codePoint == ANUSVARA || codePoint == CHANDRABINDU) {
        out.add('n', source);
        continue;
      }
      if (codePoint == VISARGA) {
        out.add('h', source);
        continue;
      }
      String orphan = lookup(MATRAS, codePoint);
      if (orphan != null) {
        // Orphan matra (no preceding consonant) — keep its vowel.
        out.addAll(orphan, source);
        continue;
      }

      out.add(codePoint, source); // not Devanagari, pass through untouched
    }

    return out;
  }

  /* ------------------------------------------------------------------ *
   * Step 4 — collapse repeated characters
   * ------------------------------------------------------------------ */

  /** A run must reach this length before it collapses. */
  private static final int MIN_RUN = 3;

  /** A token must stay at least this long for a trailing double to collapse. */
  private static final int MIN_COLLAPSED_TOKEN = 4;

  /**
   * JS {@code /\p{L}|\p{N}/u}: General_Category L* or N*.
   *
   * <p>Uses {@link Character#getType(int)} rather than {@code isLetter} or
   * {@code isLetterOrDigit}, which do not cover Nl/No. The result still follows
   * the JDK's Unicode version (13 on Java 17), which is older than Node's.
   */
  static boolean isWordChar(int codePoint) {
    if (codePoint < 0x80) {
      return (codePoint >= 'a' && codePoint <= 'z')
          || (codePoint >= 'A' && codePoint <= 'Z')
          || (codePoint >= '0' && codePoint <= '9');
    }
    switch (Character.getType(codePoint)) {
      case Character.UPPERCASE_LETTER:
      case Character.LOWERCASE_LETTER:
      case Character.TITLECASE_LETTER:
      case Character.MODIFIER_LETTER:
      case Character.OTHER_LETTER:
      case Character.DECIMAL_DIGIT_NUMBER:
      case Character.LETTER_NUMBER:
      case Character.OTHER_NUMBER:
        return true;
      default:
        return false;
    }
  }

  /**
   * {@code fuuuuck} → {@code fuck}, {@code LUNDDD} → {@code lund}.
   *
   * <p>Only runs of three or more collapse; a run of two collapses only at the
   * end of a token that stays at least {@link #MIN_COLLAPSED_TOKEN} long. The
   * surviving cell is the FIRST of the run, which is why a match on
   * {@code fuckkk} ends at the first {@code k}.
   */
  private static Cells collapseRepeats(Cells cells) {
    Cells out = new Cells(cells.size);
    int tokenLen = 0; // characters emitted in the current token so far
    int i = 0;

    while (i < cells.size) {
      int codePoint = cells.cp[i];
      int run = 1;
      while (i + run < cells.size && cells.cp[i + run] == codePoint) run++;

      if (!isWordChar(codePoint)) {
        // separator: ends the current token
        for (int k = 0; k < run; k++) out.add(cells.cp[i + k], cells.src[i + k]);
        tokenLen = 0;
        i += run;
        continue;
      }

      boolean atTokenEnd = i + run >= cells.size || !isWordChar(cells.cp[i + run]);
      boolean collapse =
          run >= MIN_RUN || (run == 2 && atTokenEnd && tokenLen + 1 >= MIN_COLLAPSED_TOKEN);

      if (collapse) {
        out.add(codePoint, cells.src[i]);
        tokenLen += 1;
      } else {
        for (int k = 0; k < run; k++) out.add(cells.cp[i + k], cells.src[i + k]);
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
   * Digits and symbols standing in for letters. {@code 1}, {@code l}, {@code i}
   * and {@code |} all fold onto {@code i}, so {@code lund} is compared as
   * {@code iund} on both sides. {@code !} is deliberately absent.
   */
  private static int leet(int codePoint) {
    switch (codePoint) {
      case '0': return 'o';
      case '3': return 'e';
      case '4': return 'a';
      case '5': return 's';
      case '6': return 'g';
      case '7': return 't';
      case '8': return 'b';
      case '9': return 'g';
      case '@': return 'a';
      case '$': return 's';
      case '+': return 't';
      case '(': return 'c';
      case 0x20AC: return 'e'; // €
      case 0x00A3: return 'e'; // £
      case 0x00A2: return 'c'; // ¢
      case '1': return 'i';
      case 'l': return 'i';
      case '|': return 'i';
      default: return codePoint;
    }
  }

  private static Cells decodeLeet(Cells cells) {
    Cells out = new Cells(cells.size);
    for (int i = 0; i < cells.size; i++) out.add(leet(cells.cp[i]), cells.src[i]);
    return out;
  }

  /* ------------------------------------------------------------------ *
   * Step 6 — separators
   * ------------------------------------------------------------------ */

  private static boolean isZeroWidth(int codePoint) {
    return (codePoint >= 0x200B && codePoint <= 0x200D) || codePoint == 0xFEFF || codePoint == 0x00AD;
  }

  /** True for anything that is not a letter or digit: spaces, punctuation, emoji, zero-width. */
  private static boolean isSeparator(int codePoint) {
    if (codePoint == WILDCARD) return false;
    if (isZeroWidth(codePoint)) return true;
    return !isWordChar(codePoint);
  }

  private static boolean isMaskChar(int codePoint) {
    return codePoint == '*' || codePoint == '#' || codePoint == '%' || codePoint == '&' || codePoint == '!';
  }

  /**
   * Turns a masking character between two letters into {@link #WILDCARD}, and
   * drops it elsewhere. Neighbours are read from the INPUT cells, so in
   * {@code l**nd} neither {@code *} is flanked and both are dropped.
   */
  private static Cells markWildcards(Cells cells) {
    Cells out = new Cells(cells.size);
    for (int i = 0; i < cells.size; i++) {
      int codePoint = cells.cp[i];
      if (!isMaskChar(codePoint)) {
        out.add(codePoint, cells.src[i]);
        continue;
      }
      boolean flanked =
          i > 0 && isWordChar(cells.cp[i - 1]) && i + 1 < cells.size && isWordChar(cells.cp[i + 1]);
      if (flanked) out.add(WILDCARD, cells.src[i]);
    }
    return out;
  }

  /* ------------------------------------------------------------------ *
   * Rendering and tokenization
   * ------------------------------------------------------------------ */

  /** Splits on separators, keeping each token's offsets in the original input. */
  private static List<Token> tokenize(Cells cells) {
    List<Token> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int first = -1;
    int last = -1;

    for (int i = 0; i <= cells.size; i++) {
      boolean separator = i == cells.size || isSeparator(cells.cp[i]);
      if (!separator) {
        if (first < 0) first = i;
        last = i;
        current.appendCodePoint(cells.cp[i]);
        continue;
      }
      if (first >= 0) {
        // End = start of the last cell + the length of its NORMALIZED character,
        // as in JS. For an astral original that normalizes to a BMP letter this
        // lands inside a surrogate pair. See KNOWN_ISSUES.md (P1).
        int end = cells.src[last] + Character.charCount(cells.cp[last]);
        tokens.add(new Token(current.toString(), cells.src[first], end));
        current.setLength(0);
        first = -1;
      }
    }
    return tokens;
  }

  /* ------------------------------------------------------------------ *
   * The pipeline
   * ------------------------------------------------------------------ */

  /** Runs the full pipeline. */
  static Normalized normalize(String input) {
    // Devanagari pass: lowercase + NFC only. No transliteration, no collapsing.
    Cells devaCells = stripLatinMarks(lowercase(toCells(input)));

    StringBuilder deva = new StringBuilder(devaCells.size);
    int[] devaMap = new int[devaCells.size * 2];
    int devaLen = 0;
    for (int i = 0; i < devaCells.size; i++) {
      deva.appendCodePoint(devaCells.cp[i]);
      for (int k = Character.charCount(devaCells.cp[i]); k > 0; k--) devaMap[devaLen++] = devaCells.src[i];
    }

    // Latin pass: the full pipeline.
    Cells cells = devaCells;
    cells = transliterate(cells);
    cells = collapseRepeats(cells);
    cells = decodeLeet(cells);
    cells = markWildcards(cells);

    List<Token> tokens = tokenize(cells);

    StringBuilder cleaned = new StringBuilder(cells.size);
    int[] cleanedMap = new int[cells.size * 2];
    int cleanedLen = 0;
    for (int i = 0; i < cells.size; i++) {
      if (isSeparator(cells.cp[i])) continue;
      cleaned.appendCodePoint(cells.cp[i]);
      for (int k = Character.charCount(cells.cp[i]); k > 0; k--) cleanedMap[cleanedLen++] = cells.src[i];
    }

    return new Normalized(deva.toString(), devaMap, cleaned.toString(), cleanedMap, tokens);
  }

  /**
   * Normalizes a wordlist term the same way as input, so both sides of a
   * comparison are in the same alphabet.
   */
  static String normalizeTerm(String term) {
    return normalize(term).cleaned();
  }
}
