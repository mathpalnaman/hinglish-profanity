package io.github.mathpalnaman.hinglishprofanity;

/** Which wordlist a term came from. */
public enum Lang {
  EN("en"),
  HI_DEVA("hi-deva"),
  HI_LATIN("hi-latin");

  private final String jsValue;

  Lang(String jsValue) {
    this.jsValue = jsValue;
  }

  /** The string the JavaScript package uses, and the value of {@link Match#lang()}. */
  public String jsValue() {
    return jsValue;
  }

  /**
   * @throws IllegalArgumentException if {@code value} is not one of the JS strings
   */
  public static Lang fromJsValue(String value) {
    for (Lang lang : values()) {
      if (lang.jsValue.equals(value)) return lang;
    }
    throw new IllegalArgumentException("Unknown lang: " + value);
  }
}
