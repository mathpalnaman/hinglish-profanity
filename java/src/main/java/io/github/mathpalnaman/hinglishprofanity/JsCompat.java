package io.github.mathpalnaman.hinglishprofanity;

import java.util.ArrayList;
import java.util.List;

/**
 * The few places where JavaScript and Java built-ins disagree, pinned to the
 * JavaScript behaviour so both implementations return identical results.
 */
final class JsCompat {

  private JsCompat() {}

  /**
   * JS {@code \s}: ECMAScript WhiteSpace plus LineTerminator. Java's {@code \s}
   * and {@link Character#isWhitespace(char)} are different sets (no U+00A0, no
   * U+FEFF), which is why this is spelled out.
   */
  static boolean isWhitespace(char c) {
    switch (c) {
      case '\t':
      case '\n':
      case 0x000B:
      case '\f':
      case '\r':
      case ' ':
      case 0x00A0:
      case 0x1680:
      case 0x2028:
      case 0x2029:
      case 0x202F:
      case 0x205F:
      case 0x3000:
      case 0xFEFF:
        return true;
      default:
        return c >= 0x2000 && c <= 0x200A;
    }
  }

  static boolean containsWhitespace(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (isWhitespace(text.charAt(i))) return true;
    }
    return false;
  }

  /** JS {@code String.prototype.trim}. */
  static String trim(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && isWhitespace(text.charAt(start))) start++;
    while (end > start && isWhitespace(text.charAt(end - 1))) end--;
    return text.substring(start, end);
  }

  /** JS {@code text.split(/\s+/).filter(p => p.length > 0)}. */
  static String[] splitOnWhitespace(String text) {
    List<String> parts = new ArrayList<>();
    int start = -1;
    for (int i = 0; i <= text.length(); i++) {
      boolean boundary = i == text.length() || isWhitespace(text.charAt(i));
      if (!boundary) {
        if (start < 0) start = i;
      } else if (start >= 0) {
        parts.add(text.substring(start, i));
        start = -1;
      }
    }
    return parts.toArray(new String[0]);
  }

  /**
   * JS {@code Math.round(score * 100) / 100}.
   *
   * <p>{@link Math#round(double)} returns a {@code long}: it turns NaN into 0
   * and clamps large values, where JS passes both through. Rounding itself is
   * the same (ties toward positive infinity).
   */
  static double roundScore(double score) {
    double scaled = score * 100;
    if (Double.isNaN(scaled) || Double.isInfinite(scaled) || Math.abs(scaled) >= 4503599627370496.0) {
      return scaled / 100;
    }
    return Math.round(scaled) / 100.0;
  }
}
