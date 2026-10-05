package io.github.mathpalnaman.hinglishprofanity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks on the shared wordlists: the self-check that test/wordlists.test.ts
 * runs on the JS side, the real JSON Schema, and the build stamps.
 */
class WordlistTest {

  static Stream<Arguments> entries() {
    List<Arguments> out = new ArrayList<>();
    for (String file : Fixtures.WORDLIST_FILES) {
      JsonNode list = Fixtures.readJson("src/wordlists/" + file);
      String lang = list.get("lang").asText();
      for (String key : List.of("terms", "phrases")) {
        for (JsonNode entry : list.path(key)) {
          out.add(Arguments.of(lang + " " + Fixtures.printable(entry.get("term").asText()), lang, entry.get("term").asText()));
        }
      }
    }
    return out.stream();
  }

  /** A term that cannot be found when it is the entire input can never match anything. */
  @ParameterizedTest(name = "{0} detects itself at index 0")
  @MethodSource("entries")
  void detectsItself(String name, String lang, String term) {
    Result result = HinglishProfanity.defaults().detect(term);
    Match hit = result.matches().stream().filter(m -> m.term().equals(term)).findFirst().orElse(null);
    assertNotNull(hit, "\"" + term + "\" is not reported for its own text: " + result.matches());
    assertEquals(0, hit.index());
    assertEquals(lang, hit.lang());
  }

  @ParameterizedTest(name = "{0} normalizes idempotently")
  @MethodSource("entries")
  void normalizesIdempotently(String name, String lang, String term) {
    for (String word : JsCompat.splitOnWhitespace(term)) {
      String once = Normalizer.normalizeTerm(word);
      assertFalse(once.isEmpty());
      assertEquals(once, Normalizer.normalizeTerm(once));
    }
  }

  /**
   * The Gradle build restates schema.json by hand, because it cannot depend on
   * a validator. This runs the real schema, so a rule added there and forgotten
   * in build.gradle still fails a build.
   */
  @Test
  void wordlistsSatisfyTheRealSchema() {
    JsonSchema schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Fixtures.readJson("src/wordlists/schema.json"));
    for (String file : Fixtures.WORDLIST_FILES) {
      Set<ValidationMessage> errors = schema.validate(Fixtures.readJson("src/wordlists/" + file));
      assertTrue(errors.isEmpty(), file + ": " + errors);
    }
  }

  /** The jar carries every entry of the JSON files, flags included. */
  @Test
  void generatedResourceMatchesTheJsonFiles() {
    int expected = 0;
    for (String file : Fixtures.WORDLIST_FILES) {
      JsonNode list = Fixtures.readJson("src/wordlists/" + file);
      Lang lang = Lang.fromJsValue(list.get("lang").asText());
      for (String key : List.of("terms", "phrases")) {
        for (JsonNode entry : list.path(key)) {
          expected++;
          Entry want =
              new Entry(
                  entry.get("term").asText(),
                  entry.get("severity").asInt(),
                  entry.path("wholeTokenOnly").asBoolean(false),
                  entry.path("ambiguous").asBoolean(false));
          boolean phrase = key.equals("phrases");
          assertTrue(
              Wordlists.lines().contains(new Wordlists.Line(lang, phrase, want)),
              file + ": missing " + want);
        }
      }
    }
    assertEquals(expected, Wordlists.lines().size());
  }

  @Test
  void versionMatchesPackageJson() {
    assertEquals(Fixtures.readJson("package.json").get("version").asText(), HinglishProfanity.VERSION);
  }

  /** parity.json records the hash the JS build computed; both builds must agree. */
  @Test
  void wordlistHashMatchesTheJavaScriptBuild() {
    assertTrue(HinglishProfanity.WORDLIST_HASH.matches("[0-9a-f]{12}"));
    assertEquals(
        Fixtures.readJson("test/fixtures/parity.json").get("wordlistHash").asText(),
        HinglishProfanity.WORDLIST_HASH);
  }
}
