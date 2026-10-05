package io.github.mathpalnaman.hinglishprofanity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs test/fixtures/corpus.jsonl against the Java detector, with the same
 * pass/fail rules as test/corpus.test.ts.
 *
 * <p>The corpus is the spec. A failing case means the implementation is wrong
 * until proven otherwise — never edit a case to make the code pass.
 */
class CorpusTest {

  private static final List<JsonNode> CASES = Fixtures.corpus();

  private static Stream<Arguments> select(boolean positive) {
    return CASES.stream()
        .filter(c -> c.get("expect").isArray() && (c.get("expect").size() > 0) == positive)
        .map(c -> Arguments.of(c.get("id").asText() + ": " + Fixtures.printable(c.get("input").asText()), c));
  }

  static Stream<Arguments> positives() {
    return select(true);
  }

  static Stream<Arguments> negatives() {
    return select(false);
  }

  static Stream<Arguments> contracts() {
    return CASES.stream()
        .filter(c -> !c.get("expect").isArray())
        .map(c -> Arguments.of(c.get("id").asText(), c));
  }

  @Test
  void hasUniqueIdsAndParses() {
    Set<String> ids = new HashSet<>();
    for (JsonNode c : CASES) ids.add(c.get("id").asText());
    assertEquals(CASES.size(), ids.size());
    assertTrue(CASES.size() > 100);
  }

  /** Known gaps (homoglyphs, vowel-drop) are skipped, as in the JS suite. */
  private static void skipKnownGaps(JsonNode testCase) {
    assumeFalse("day2".equals(testCase.path("phase").asText()), "phase day2: known gap");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("positives")
  void mustBeDetected(String name, JsonNode testCase) {
    skipKnownGaps(testCase);
    String id = testCase.get("id").asText();
    String input = testCase.get("input").asText();
    Result result = Fixtures.detectorFor(testCase.get("options")).detect(input);

    for (JsonNode want : testCase.get("expect")) {
      String term = want.get("term").asText();
      Match got = result.matches().stream().filter(m -> m.term().equals(term)).findFirst().orElse(null);
      assertNotNull(got, id + ": expected term \"" + term + "\", got " + result.matches());

      if (want.has("lang")) assertEquals(want.get("lang").asText(), got.lang());
      if (want.has("severity")) assertEquals(want.get("severity").asInt(), got.severity());
      if (want.has("confidence")) assertEquals(want.get("confidence").asText(), got.confidence().jsValue());

      // Offsets must point at the real text, not at the normalized text.
      assertEquals(
          got.matchedText(),
          input.substring(got.index(), got.endIndex()),
          id + ": matchedText must equal the slice it claims");
    }

    assertTrue(result.score() > 0);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("negatives")
  void mustPassClean(String name, JsonNode testCase) {
    skipKnownGaps(testCase);
    Result result = Fixtures.detectorFor(testCase.get("options")).detect(testCase.get("input").asText());

    assertEquals(
        List.of(),
        result.matches(),
        testCase.get("id").asText() + " (" + testCase.path("note").asText("no note") + ")");
    assertEquals(0.0, result.score());
  }

  /**
   * err-001: a null input throws. JS throws TypeError; the Java equivalent of
   * "you passed something that is not a string" is NullPointerException.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("contracts")
  void contract(String name, JsonNode testCase) {
    assertEquals("TypeError", testCase.get("expect").get("throws").asText());
    assertTrue(testCase.get("input").isNull(), "the only contract case today is a null input");
    assertThrows(NullPointerException.class, () -> HinglishProfanity.defaults().detect(null));
  }
}
