package io.github.mathpalnaman.hinglishprofanity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The built-in wordlists.
 *
 * <p>The source of truth is {@code src/wordlists/*.json}, shared with the
 * JavaScript package. The Gradle build validates those files and flattens them
 * into {@code wordlists.tsv}, so no JSON parser is needed at runtime:
 *
 * <pre>lang TAB kind(T|P) TAB severity TAB wholeTokenOnly(0|1) TAB ambiguous(0|1) TAB term</pre>
 *
 * Lines are in the order the JS matcher consumes them (en, hi-latin, hi-deva;
 * terms before phrases), which the match order depends on.
 */
final class Wordlists {

  private Wordlists() {}

  record Line(Lang lang, boolean phrase, Entry entry) {}

  private static final String RESOURCE = "wordlists.tsv";

  /** Initialization-on-demand holder: loaded once, on first use, thread-safely. */
  private static final class Holder {
    static final List<Line> LINES = load();
  }

  static List<Line> lines() {
    return Holder.LINES;
  }

  private static List<Line> load() {
    // getResourceAsStream only: a classpath resource inside a nested jar (Spring
    // Boot's executable jar) has no file path, so File/Path/toURI would fail.
    InputStream in = HinglishProfanity.class.getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IllegalStateException("hinglish-profanity: missing classpath resource " + RESOURCE);
    }
    List<Line> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String row;
      while ((row = reader.readLine()) != null) {
        if (row.isEmpty()) continue;
        String[] f = row.split("\t", 6);
        if (f.length != 6) throw new IllegalStateException("hinglish-profanity: malformed wordlist row: " + row);
        Entry entry = new Entry(f[5], Integer.parseInt(f[2]), f[3].equals("1"), f[4].equals("1"));
        lines.add(new Line(Lang.fromJsValue(f[0]), f[1].equals("P"), entry));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return List.copyOf(lines);
  }
}
