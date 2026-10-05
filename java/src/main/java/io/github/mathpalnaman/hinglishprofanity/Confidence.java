package io.github.mathpalnaman.hinglishprofanity;

/**
 * How reliable one particular match is. Orthogonal to severity, which describes
 * the word.
 *
 * <ul>
 *   <li>{@link #HIGH} — the term was found intact (exact, spaced-out, or leet-decoded).
 *   <li>{@link #LOW} — the term itself is ambiguous ({@code bc}, {@code laura}),
 *       or it was found by a lossy method (vowel-drop). Treat as "needs review".
 * </ul>
 */
public enum Confidence {
  HIGH("high"),
  LOW("low");

  private final String jsValue;

  Confidence(String jsValue) {
    this.jsValue = jsValue;
  }

  /** The string the JavaScript package returns: {@code "high"} or {@code "low"}. */
  public String jsValue() {
    return jsValue;
  }

  /**
   * @throws IllegalArgumentException if {@code value} is not one of the JS strings
   */
  public static Confidence fromJsValue(String value) {
    for (Confidence confidence : values()) {
      if (confidence.jsValue.equals(value)) return confidence;
    }
    throw new IllegalArgumentException("Unknown confidence: " + value);
  }
}
