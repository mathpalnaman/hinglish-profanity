package io.github.mathpalnaman.hinglishprofanity;

/** Which strategy produced the match. */
public enum Method {
  /** Normalized token equals a wordlist term. */
  EXACT("exact"),
  /** Found with separators or mask characters in it: {@code l u n d}, {@code f.u.c.k}, {@code l*nd}. */
  SPACED("spaced"),
  /** Found after leet decoding: {@code ch00tiya}, {@code 1und}. */
  LEET("leet"),
  /** Multi-word wordlist entry: {@code bhen ke lode}, {@code piece of shit}. */
  PHRASE("phrase"),
  /** Found after dropping vowels: {@code fck}. Opt-in; always {@link Confidence#LOW}. */
  VOWEL_DROP("vowel-drop");

  private final String jsValue;

  Method(String jsValue) {
    this.jsValue = jsValue;
  }

  /** The string the JavaScript package returns, e.g. {@code "leet"}, {@code "vowel-drop"}. */
  public String jsValue() {
    return jsValue;
  }

  /**
   * @throws IllegalArgumentException if {@code value} is not one of the JS strings
   */
  public static Method fromJsValue(String value) {
    for (Method method : values()) {
      if (method.jsValue.equals(value)) return method;
    }
    throw new IllegalArgumentException("Unknown method: " + value);
  }
}
