package io.github.mathpalnaman.hinglishprofanity;

import java.util.Objects;

/**
 * A wordlist entry, for {@link Detector.Builder#extraWords}.
 *
 * @param term canonical spelling, reported as {@link Match#term()}
 * @param severity how offensive the WORD is, 1 (mild) to 4 (slurs, sexual abuse)
 * @param wholeTokenOnly only match as a complete token, never inside a longer
 *     word. Needed for short stems: {@code ass} in {@code assignment}.
 * @param ambiguous the term collides with innocent usage; matches on it are
 *     reported with {@link Confidence#LOW}
 */
public record Entry(String term, int severity, boolean wholeTokenOnly, boolean ambiguous) {

  public Entry {
    Objects.requireNonNull(term, "term");
    if (severity < 1 || severity > 4) {
      throw new IllegalArgumentException("severity must be 1-4, got " + severity);
    }
  }

  public Entry(String term, int severity) {
    this(term, severity, false, false);
  }
}
