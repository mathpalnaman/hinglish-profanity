package io.github.mathpalnaman.hinglishprofanity;

import io.github.mathpalnaman.hinglishprofanity.Normalizer.Normalized;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scans text for profanity. Immutable and thread-safe: build one and reuse it.
 *
 * <p>All wordlist preparation happens in {@link Builder#build()}; {@link #detect}
 * only normalizes its input and looks things up.
 */
public final class Detector {

  /** Default score weights. Doubling per level: one level-4 hit outranks any pile of level-1s. */
  private static final double[] DEFAULT_WEIGHTS = {0, 1, 2, 4, 8};

  private final Matcher.Lists lists;
  private final List<String> allowNorms;
  private final boolean vowelDrop;
  /** Indexed by severity 1-4. */
  private final double[] weights;

  private Detector(Builder builder) {
    // JS quirk, mirrored: an empty langs list means "all lists" on its own, but
    // "no built-in lists" once extraWords is non-empty. See KNOWN_ISSUES.md (P3).
    boolean custom =
        (builder.langs != null && !builder.langs.isEmpty()) || !builder.extraWords.isEmpty();
    this.lists = Matcher.prepare(custom ? builder.langs : null, builder.extraWords);

    List<String> norms = new ArrayList<>();
    for (String phrase : builder.allowlist) norms.add(Normalizer.normalizeTerm(phrase));
    this.allowNorms = List.copyOf(norms);

    this.vowelDrop = builder.vowelDrop;

    this.weights = DEFAULT_WEIGHTS.clone();
    for (int severity = 1; severity <= 4; severity++) {
      Double weight = builder.weights.get(severity);
      if (weight != null) this.weights[severity] = weight;
    }
  }

  /**
   * Scans {@code text} and returns every match found, with positions into the
   * original string.
   *
   * @param text raw text. Blank text returns an empty result.
   * @throws NullPointerException if {@code text} is null. A rating-only review
   *     has no text; filter those out before calling. (The JavaScript package
   *     throws {@code TypeError} here.)
   */
  public Result detect(String text) {
    Objects.requireNonNull(text, "hinglish-profanity: expected a string, received null");

    Normalized normalized = Normalizer.normalize(text);
    List<Match> matches = Matcher.findMatches(text, normalized, lists, vowelDrop, allowNorms);

    // A confidence: low match counts half: an ambiguous term or a vowel-dropped
    // hit is evidence of a possible problem, not of a definite one.
    double score = 0;
    for (Match match : matches) {
      double weight = weights[match.severity()];
      score += match.confidence() == Confidence.LOW ? weight / 2 : weight;
    }

    return new Result(JsCompat.roundScore(score), matches, normalized.cleaned());
  }

  /** Options for a {@link Detector}. Every one of them is off or empty by default. */
  public static final class Builder {
    private Set<Lang> langs;
    private List<String> allowlist = List.of();
    private List<Entry> extraWords = List.of();
    private boolean vowelDrop;
    private Map<Integer, Double> weights = Map.of();

    Builder() {}

    /**
     * Restricts which wordlists are consulted. Default: all three. Narrowing
     * this is the cheapest way to cut false positives.
     */
    public Builder langs(Set<Lang> langs) {
      Objects.requireNonNull(langs, "langs");
      this.langs = langs.isEmpty() ? EnumSet.noneOf(Lang.class) : EnumSet.copyOf(langs);
      return this;
    }

    /**
     * Phrases that suppress any match overlapping them. Matched against the
     * normalized text.
     *
     * <p>Pass phrases, not bare words: allowlisting {@code "lund"} also
     * suppresses real abuse. Allowlist the name ({@code "dr lund"}), not the word.
     */
    public Builder allowlist(List<String> phrases) {
      this.allowlist = List.copyOf(phrases);
      return this;
    }

    /** Extra terms to match, merged with the built-in lists. */
    public Builder extraWords(List<Entry> entries) {
      this.extraWords = List.copyOf(entries);
      return this;
    }

    /**
     * Also try matching with vowels removed ({@code fck} → {@code fuck}). Off by
     * default and usually best left off: it is noisy, and every match it
     * produces is {@link Confidence#LOW}.
     */
    public Builder vowelDrop(boolean on) {
      this.vowelDrop = on;
      return this;
    }

    /**
     * Per-severity weights used to compute {@link Result#score()}. Default:
     * {@code {1:1, 2:2, 3:4, 4:8}}. Severities missing from the map keep their
     * default. A {@link Confidence#LOW} match contributes half its weight.
     */
    public Builder weights(Map<Integer, Double> weights) {
      this.weights = Map.copyOf(weights);
      return this;
    }

    /** Prepares the wordlists and indexes. Do this once, not per call. */
    public Detector build() {
      return new Detector(this);
    }
  }
}
