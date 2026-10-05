package io.github.mathpalnaman.hinglishprofanity;

import io.github.mathpalnaman.hinglishprofanity.Normalizer.Normalized;
import io.github.mathpalnaman.hinglishprofanity.Normalizer.Token;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The matcher. A port of {@code src/match.ts}.
 *
 * <p>Takes normalized text (plus its offset maps) and prepared wordlists, and
 * produces evidence. It makes no decisions: no thresholds, no blocking, no
 * boolean.
 *
 * <p>The passes run in the same order as JS and each emits matches in the same
 * order, because {@link #dedupe} is order-sensitive: of two different terms
 * covering the same span, the one found first wins. The hash indexes below are
 * lookups that return entries in wordlist order; they change the cost, never
 * the output.
 */
final class Matcher {

  private Matcher() {}

  /** A wordlist entry with its normalized form precomputed. */
  static final class Prepared {
    /** Canonical term as written in the wordlist (reported as {@code Match.term}). */
    final String term;
    /** Normalized form, compared against normalized input. */
    final String norm;
    /** For phrases: the normalized form split into tokens. */
    final String[] parts;
    final int severity;
    final boolean wholeTokenOnly;
    final boolean ambiguous;
    final Lang lang;
    final boolean isPhrase;
    /** {@link #norm} with vowels removed, for the opt-in vowel-drop pass. */
    final String bare;

    Prepared(
        String term,
        String norm,
        String[] parts,
        int severity,
        boolean wholeTokenOnly,
        boolean ambiguous,
        Lang lang,
        boolean isPhrase) {
      this.term = term;
      this.norm = norm;
      this.parts = parts;
      this.severity = severity;
      this.wholeTokenOnly = wholeTokenOnly;
      this.ambiguous = ambiguous;
      this.lang = lang;
      this.isPhrase = isPhrase;
      this.bare = dropVowels(norm);
    }
  }

  /** Prepared wordlists plus the lookups derived from them. Immutable once built. */
  static final class Lists {
    /** Latin-alphabet entries, in JS order: en, hi-latin, then extraWords. */
    final List<Prepared> latin;
    /** Devanagari entries, matched against near-raw text before transliteration. */
    final List<Prepared> deva;

    /** Non-phrase Latin entries by normalized form, each list in wordlist order. */
    final Map<String, List<Prepared>> byNorm = new HashMap<>();
    /** Non-phrase Latin entries by vowel-dropped form (3+ chars), in wordlist order. */
    final Map<String, List<Prepared>> byBare = new HashMap<>();
    /** Longest normalized non-phrase Latin term; a token run longer than this cannot match. */
    final int maxNormLength;

    Lists(List<Prepared> latin, List<Prepared> deva) {
      this.latin = List.copyOf(latin);
      this.deva = List.copyOf(deva);

      int longest = 0;
      for (Prepared entry : this.latin) {
        if (entry.isPhrase) continue;
        byNorm.computeIfAbsent(entry.norm, k -> new ArrayList<>()).add(entry);
        if (entry.bare.length() >= MIN_BARE) {
          byBare.computeIfAbsent(entry.bare, k -> new ArrayList<>()).add(entry);
        }
        longest = Math.max(longest, entry.norm.length());
      }
      this.maxNormLength = longest;
    }
  }

  /* ------------------------------------------------------------------ *
   * Preparing wordlists
   * ------------------------------------------------------------------ */

  /** Devanagari terms only get the light touch the Devanagari pass applies: lowercase + NFC. */
  private static String normalizeFor(Lang lang, String text) {
    return lang == Lang.HI_DEVA
        ? java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), Form.NFC)
        : Normalizer.normalizeTerm(text);
  }

  private static Prepared prepareEntry(Entry entry, Lang lang, boolean isPhrase) {
    String norm = normalizeFor(lang, entry.term());
    String[] parts = isPhrase ? JsCompat.splitOnWhitespace(norm) : new String[] {norm};
    return new Prepared(
        entry.term(), norm, parts, entry.severity(), entry.wholeTokenOnly(), entry.ambiguous(), lang, isPhrase);
  }

  /**
   * Normalizes every wordlist entry once, so matching is string comparison.
   *
   * @param langs {@code null} when the consumer did not restrict languages. An
   *     EMPTY set loads no built-in list at all, as in JS; the caller decides
   *     whether an empty set reaches this method (see {@code Detector.Builder}).
   */
  static Lists prepare(Set<Lang> langs, List<Entry> extraWords) {
    List<Prepared> latin = new ArrayList<>();
    List<Prepared> deva = new ArrayList<>();

    for (Wordlists.Line line : Wordlists.lines()) {
      Lang lang = line.lang();
      if (langs != null && !langs.contains(lang)) continue;
      Entry entry = line.entry();
      List<Prepared> target = lang == Lang.HI_DEVA ? deva : latin;

      if (!line.phrase()) {
        target.add(prepareEntry(entry, lang, false));
        continue;
      }

      // A phrase is multi-word; normalizeTerm strips the spaces, so phrases are
      // normalized word by word instead.
      List<String> parts = new ArrayList<>();
      for (String word : JsCompat.splitOnWhitespace(entry.term())) {
        String norm = normalizeFor(lang, word);
        if (!norm.isEmpty()) parts.add(norm);
      }
      target.add(
          new Prepared(
              entry.term(),
              String.join(" ", parts),
              parts.toArray(new String[0]),
              entry.severity(),
              entry.wholeTokenOnly(),
              entry.ambiguous(),
              lang,
              true));
    }

    for (Entry entry : extraWords) {
      Lang lang = containsDevanagari(entry.term()) ? Lang.HI_DEVA : Lang.EN;
      boolean isPhrase = JsCompat.containsWhitespace(JsCompat.trim(entry.term()));
      // As in JS, an extra phrase is normalized as ONE string, which strips its
      // spaces: "bad word" becomes the single part "badword" and therefore only
      // matches the token "badword". See KNOWN_ISSUES.md (P2).
      (lang == Lang.HI_DEVA ? deva : latin).add(prepareEntry(entry, lang, isPhrase));
    }

    return new Lists(latin, deva);
  }

  private static boolean containsDevanagari(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (isDevanagariUnit(text.charAt(i))) return true;
    }
    return false;
  }

  private static boolean isDevanagariUnit(char unit) {
    return unit >= 0x0900 && unit <= 0x097F;
  }

  /* ------------------------------------------------------------------ *
   * Wildcard-aware search
   * ------------------------------------------------------------------ */

  /** True if {@code haystack} matches {@code needle} at {@code at}, treating WILDCARD as any one char. */
  private static boolean matchesAt(String haystack, String needle, int at) {
    if (at + needle.length() > haystack.length()) return false;
    for (int i = 0; i < needle.length(); i++) {
      char h = haystack.charAt(at + i);
      if (h == Normalizer.WILDCARD) continue; // `l*nd`: the masked character matches anything
      if (h != needle.charAt(i)) return false;
    }
    return true;
  }

  private static boolean hasWildcard(String text) {
    return text.indexOf(Normalizer.WILDCARD) >= 0;
  }

  /* ------------------------------------------------------------------ *
   * Method / confidence
   * ------------------------------------------------------------------ */

  /** JS {@code /[\s.\-_*|!]/}. */
  private static boolean hasSpacingChar(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '.' || c == '-' || c == '_' || c == '*' || c == '|' || c == '!') return true;
      if (JsCompat.isWhitespace(c)) return true;
    }
    return false;
  }

  /** JS {@code /[0-9@$+(€£¢]/}. */
  private static boolean hasLeetChar(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if ((c >= '0' && c <= '9') || c == '@' || c == '$' || c == '+' || c == '(') return true;
      if (c == '€' || c == '£' || c == '¢') return true;
    }
    return false;
  }

  /**
   * Describes how the input differed from the canonical term. Normalization is
   * lossy by design, so this is inferred from the raw slice.
   */
  private static Method methodFor(String rawSlice, String term, Method fallback) {
    // Whole-string lowercasing here (unlike the per-code-point normalizer step),
    // as in JS.
    if (rawSlice.toLowerCase(Locale.ROOT).equals(term.toLowerCase(Locale.ROOT))) {
      return fallback == Method.PHRASE ? Method.PHRASE : Method.EXACT;
    }
    if (hasSpacingChar(rawSlice)) return fallback == Method.PHRASE ? Method.PHRASE : Method.SPACED;
    if (hasLeetChar(rawSlice)) return Method.LEET;
    return fallback;
  }

  private static Match makeMatch(String input, Prepared entry, int start, int end, Method fallback) {
    String matchedText = input.substring(start, end);
    Method method = fallback == Method.VOWEL_DROP ? Method.VOWEL_DROP : methodFor(matchedText, entry.term, fallback);
    Confidence confidence = entry.ambiguous || method == Method.VOWEL_DROP ? Confidence.LOW : Confidence.HIGH;
    return new Match(
        entry.term, matchedText, start, end, entry.severity, confidence, method, entry.lang.jsValue());
  }

  /* ------------------------------------------------------------------ *
   * The individual matchers
   * ------------------------------------------------------------------ */

  /**
   * Non-phrase Latin entries whose normalized form equals {@code text}, in
   * wordlist order. Text carrying a wildcard can equal several different
   * normalized forms, so it takes the linear path JS always takes.
   */
  private static Collection<Prepared> equalTo(String text, Lists lists) {
    if (!hasWildcard(text)) {
      List<Prepared> hit = lists.byNorm.get(text);
      return hit == null ? List.of() : hit;
    }
    List<Prepared> out = new ArrayList<>();
    for (Prepared entry : lists.latin) {
      if (entry.isPhrase) continue;
      if (entry.norm.length() == text.length() && matchesAt(text, entry.norm, 0)) out.add(entry);
    }
    return out;
  }

  /** Token equality: the strictest and most reliable pass. */
  private static void matchTokens(String input, List<Token> tokens, Lists lists, List<Match> out) {
    for (Token token : tokens) {
      for (Prepared entry : equalTo(token.text(), lists)) {
        out.add(makeMatch(input, entry, token.start(), token.end(), Method.EXACT));
      }
    }
  }

  /** Longest run of tokens a spaced-out term may span: {@code l . u . n . d} is 4. */
  private static final int MAX_RUN_TOKENS = 8;

  /** Shortest term allowed to match across token boundaries. */
  private static final int MIN_RUN_TERM_LEN = 4;

  /** Concatenations of consecutive tokens: catches {@code l u n d}, {@code l.u.n.d}. */
  private static void matchTokenRuns(String input, List<Token> tokens, Lists lists, List<Match> out) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      text.setLength(0);
      text.append(tokens.get(i).text());

      for (int n = 1; n < MAX_RUN_TOKENS && i + n < tokens.size(); n++) {
        Token last = tokens.get(i + n);
        text.append(last.text());
        // Tokens are never empty, so the run only grows: once it is longer than
        // every term, no later n can match either.
        if (text.length() > lists.maxNormLength) break;
        if (text.length() < MIN_RUN_TERM_LEN) continue;

        for (Prepared entry : equalTo(text.toString(), lists)) {
          out.add(makeMatch(input, entry, tokens.get(i).start(), last.end(), Method.SPACED));
        }
      }
    }
  }

  /** Multi-word entries: slide the phrase's parts over the token array. */
  private static void matchPhrases(String input, List<Token> tokens, Lists lists, List<Match> out) {
    for (Prepared entry : lists.latin) {
      if (!entry.isPhrase || entry.parts.length == 0) continue;
      for (int i = 0; i + entry.parts.length <= tokens.size(); i++) {
        boolean hit = true;
        for (int k = 0; k < entry.parts.length; k++) {
          if (!tokens.get(i + k).text().equals(entry.parts[k])) {
            hit = false;
            break;
          }
        }
        if (hit) {
          Token first = tokens.get(i);
          Token last = tokens.get(i + entry.parts.length - 1);
          out.add(makeMatch(input, entry, first.start(), last.end(), Method.PHRASE));
        }
      }
    }
  }

  /**
   * Substring scan over the separator-stripped text. Skips {@code wholeTokenOnly}
   * entries — this is the pass that would otherwise find {@code ass} inside
   * {@code assignment}.
   */
  private static void matchStripped(String input, Normalized n, Lists lists, List<Match> out) {
    String cleaned = n.cleaned();
    int[] map = n.cleanedMap();
    for (Prepared entry : lists.latin) {
      if (entry.wholeTokenOnly || entry.isPhrase) continue;
      int len = entry.norm.length();
      if (len == 0) continue;
      for (int at = 0; at + len <= cleaned.length(); at++) {
        if (!matchesAt(cleaned, entry.norm, at)) continue;
        // +1, not +charCount: JS assumes the last original character is one
        // UTF-16 unit. See KNOWN_ISSUES.md (P1).
        out.add(makeMatch(input, entry, map[at], map[at + len - 1] + 1, Method.SPACED));
      }
    }
  }

  /** Devanagari pass, run against NFC text before transliteration. */
  private static void matchDevanagari(String input, Normalized n, Lists lists, List<Match> out) {
    String deva = n.deva();
    int[] map = n.devaMap();
    for (Prepared entry : lists.deva) {
      int len = entry.norm.length();
      if (len == 0) continue;
      for (int at = 0; at + len <= deva.length(); at++) {
        if (!matchesAt(deva, entry.norm, at)) continue;
        if (entry.wholeTokenOnly) {
          boolean before = at > 0 && isDevanagariUnit(deva.charAt(at - 1));
          boolean after = at + len < deva.length() && isDevanagariUnit(deva.charAt(at + len));
          if (before || after) continue;
        }
        out.add(makeMatch(input, entry, map[at], map[at + len - 1] + 1, Method.EXACT));
      }
    }
  }

  /** Shortest vowel-dropped form that counts as evidence of anything. */
  private static final int MIN_BARE = 3;

  static String dropVowels(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u') out.append(c);
    }
    return out.toString();
  }

  /**
   * Opt-in vowel-drop pass. Whole tokens only, never substrings. Always
   * {@code confidence: low}.
   *
   * <p>Runs after leet decoding, where {@code l} is already {@code i} — so
   * {@code l} is dropped as a vowel too. See KNOWN_ISSUES.md (P3).
   */
  private static void matchVowelDrop(String input, List<Token> tokens, Lists lists, List<Match> out) {
    for (Token token : tokens) {
      String bare = dropVowels(token.text());
      if (bare.length() < MIN_BARE) continue;
      List<Prepared> candidates = lists.byBare.get(bare);
      if (candidates == null) continue;
      for (Prepared entry : candidates) {
        if (!token.text().equals(entry.norm)) {
          out.add(makeMatch(input, entry, token.start(), token.end(), Method.VOWEL_DROP));
        }
      }
    }
  }

  /* ------------------------------------------------------------------ *
   * Allowlist + dedupe
   * ------------------------------------------------------------------ */

  /** Original-text ranges covered by allowlist phrases, as {start, end} pairs. */
  private static List<int[]> allowedRanges(Normalized n, List<String> allowNorms) {
    String cleaned = n.cleaned();
    int[] map = n.cleanedMap();
    List<int[]> ranges = new ArrayList<>();
    for (String norm : allowNorms) {
      int len = norm.length();
      if (len == 0) continue;
      for (int at = 0; at + len <= cleaned.length(); at++) {
        if (matchesAt(cleaned, norm, at)) ranges.add(new int[] {map[at], map[at + len - 1] + 1});
      }
    }
    return ranges;
  }

  private static boolean overlaps(Match match, List<int[]> ranges) {
    for (int[] range : ranges) {
      if (match.index() < range[1] && range[0] < match.endIndex()) return true;
    }
    return false;
  }

  /**
   * Drops duplicates and matches swallowed by a longer one. The longest match at
   * a position wins; between different terms on the same span, the first found.
   * Both sorts are stable, as {@code Array.prototype.sort} is.
   */
  private static List<Match> dedupe(List<Match> matches) {
    List<Match> sorted = new ArrayList<>(matches);
    sorted.sort(
        (a, b) -> {
          if (a.index() != b.index()) return Integer.compare(a.index(), b.index());
          return Integer.compare(b.endIndex() - b.index(), a.endIndex() - a.index());
        });

    List<Match> kept = new ArrayList<>();
    for (Match match : sorted) {
      boolean drop = false;
      for (Match k : kept) {
        boolean swallowed =
            k.index() <= match.index() && match.endIndex() <= k.endIndex() && !k.term().equals(match.term());
        if (swallowed) {
          drop = true;
          break;
        }
      }
      if (drop) continue;
      for (Match k : kept) {
        boolean duplicate =
            k.term().equals(match.term()) && k.index() == match.index() && k.endIndex() == match.endIndex();
        if (duplicate) {
          drop = true;
          break;
        }
      }
      if (!drop) kept.add(match);
    }
    kept.sort((a, b) -> Integer.compare(a.index(), b.index()));
    return kept;
  }

  /* ------------------------------------------------------------------ *
   * Entry point
   * ------------------------------------------------------------------ */

  /**
   * Runs every enabled matcher, applies the allowlist, and returns ordered
   * evidence. The allowlist is applied BEFORE dedupe, as in JS.
   *
   * @param allowNorms allowlist phrases, already normalized
   */
  static List<Match> findMatches(
      String input, Normalized normalized, Lists lists, boolean vowelDrop, List<String> allowNorms) {
    List<Match> found = new ArrayList<>();

    if (!lists.deva.isEmpty()) matchDevanagari(input, normalized, lists, found);
    matchTokens(input, normalized.tokens(), lists, found);
    matchTokenRuns(input, normalized.tokens(), lists, found);
    matchPhrases(input, normalized.tokens(), lists, found);
    matchStripped(input, normalized, lists, found);
    if (vowelDrop) matchVowelDrop(input, normalized.tokens(), lists, found);

    if (found.isEmpty()) return found;

    if (!allowNorms.isEmpty()) {
      List<int[]> ranges = allowedRanges(normalized, allowNorms);
      if (!ranges.isEmpty()) found.removeIf(match -> overlaps(match, ranges));
    }

    return dedupe(found);
  }
}
