package io.github.mathpalnaman.hinglishprofanity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Access to the fixtures shared with the JavaScript package. They live in the
 * repository root and are read from there; nothing is copied into java/.
 */
final class Fixtures {

  private Fixtures() {}

  static final ObjectMapper JSON = new ObjectMapper();

  /** Repository root, passed in by Gradle (see the test task in build.gradle). */
  static final Path ROOT = Path.of(System.getProperty("repoRoot", "..")).toAbsolutePath().normalize();

  static final List<String> WORDLIST_FILES = List.of("en.json", "hi-deva.json", "hi-latin.json");

  static JsonNode readJson(String relativePath) {
    try {
      return JSON.readTree(Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** test/fixtures/corpus.jsonl, one case per non-blank line. */
  static List<JsonNode> corpus() {
    try {
      List<JsonNode> cases = new ArrayList<>();
      for (String line : Files.readAllLines(ROOT.resolve("test/fixtures/corpus.jsonl"), StandardCharsets.UTF_8)) {
        if (!line.isBlank()) cases.add(JSON.readTree(line));
      }
      return cases;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Builds a detector from a JS {@code DetectOptions} object. An absent or
   * empty object goes through {@link HinglishProfanity#defaults()}, so the
   * shared singleton is what the bulk of the fixtures exercise.
   */
  static Detector detectorFor(JsonNode options) {
    if (options == null || options.isNull() || options.isEmpty()) return HinglishProfanity.defaults();

    Detector.Builder builder = HinglishProfanity.builder();

    if (options.has("langs")) {
      Set<Lang> langs = EnumSet.noneOf(Lang.class);
      for (JsonNode lang : options.get("langs")) langs.add(Lang.fromJsValue(lang.asText()));
      builder.langs(langs);
    }
    if (options.has("allowlist")) {
      List<String> allowlist = new ArrayList<>();
      for (JsonNode phrase : options.get("allowlist")) allowlist.add(phrase.asText());
      builder.allowlist(allowlist);
    }
    if (options.has("extraWords")) {
      List<Entry> entries = new ArrayList<>();
      for (JsonNode e : options.get("extraWords")) {
        entries.add(
            new Entry(
                e.get("term").asText(),
                e.get("severity").asInt(),
                e.path("wholeTokenOnly").asBoolean(false),
                e.path("ambiguous").asBoolean(false)));
      }
      builder.extraWords(entries);
    }
    if (options.has("vowelDrop")) builder.vowelDrop(options.get("vowelDrop").asBoolean());
    if (options.has("weights")) {
      Map<Integer, Double> weights = new HashMap<>();
      options.get("weights").fields().forEachRemaining(f -> weights.put(Integer.parseInt(f.getKey()), f.getValue().asDouble()));
      builder.weights(weights);
    }
    return builder.build();
  }

  /**
   * ASCII-only rendering for test names. Inputs contain control characters and
   * lone surrogates, which are not legal in the XML test report.
   */
  static String printable(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length() && out.length() < 60; i++) {
      char c = text.charAt(i);
      if (c >= 0x20 && c < 0x7F) out.append(c);
      else out.append(String.format("\\u%04x", (int) c));
    }
    return out.toString();
  }
}
