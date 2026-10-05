package io.github.mathpalnaman.hinglishprofanity;

/**
 * Profanity detection for English, Hindi (Devanagari) and romanized Hinglish.
 *
 * <p>The library reports evidence. It never decides. There is no default
 * threshold, no default allowlist and no {@code isProfane} boolean, because
 * what counts as actionable depends on your product.
 *
 * <pre>{@code
 * Result result = HinglishProfanity.defaults().detect(review.getText());
 *
 * // Your policy, not the library's:
 * boolean definite = result.matches().stream()
 *     .anyMatch(m -> m.severity() >= 3 && m.confidence() == Confidence.HIGH);
 * }</pre>
 *
 * <p>Results are identical to the JavaScript package of the same version.
 */
public final class HinglishProfanity {

  private HinglishProfanity() {}

  /** Library version, identical to the npm package built from the same tag. */
  public static final String VERSION = BuildInfo.version();

  /**
   * First 12 hex chars of SHA-256 over the three wordlist files. Equal to the
   * JavaScript package's {@code WORDLIST_HASH} when both were built from the
   * same wordlists.
   */
  public static final String WORDLIST_HASH = BuildInfo.wordlistHash();

  private static final class Holder {
    static final Detector DEFAULTS = new Detector.Builder().build();
  }

  /** A detector with all languages and default options. Built once and shared. */
  public static Detector defaults() {
    return Holder.DEFAULTS;
  }

  public static Detector.Builder builder() {
    return new Detector.Builder();
  }
}
