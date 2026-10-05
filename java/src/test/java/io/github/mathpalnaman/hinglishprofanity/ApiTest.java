package io.github.mathpalnaman.hinglishprofanity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The parts of the Java API that the shared fixtures cannot express. */
class ApiTest {

  @Test
  void nullThrowsAndBlankIsEmpty() {
    Detector detector = HinglishProfanity.defaults();
    assertThrows(NullPointerException.class, () -> detector.detect(null));

    for (String blank : List.of("", "   ", "\n\t")) {
      Result result = detector.detect(blank);
      assertEquals(0.0, result.score());
      assertEquals(List.of(), result.matches());
      assertEquals("", result.cleaned());
    }
  }

  @Test
  void defaultsIsASingleton() {
    assertSame(HinglishProfanity.defaults(), HinglishProfanity.defaults());
  }

  @Test
  void returnedListsAreUnmodifiable() {
    Result result = HinglishProfanity.defaults().detect("fuck");
    assertEquals(1, result.matches().size());
    assertThrows(UnsupportedOperationException.class, () -> result.matches().clear());
  }

  @Test
  void buildersDoNotShareState() {
    List<Entry> extra = new java.util.ArrayList<>(List.of(new Entry("zzzq", 2)));
    Detector detector = HinglishProfanity.builder().extraWords(extra).build();
    extra.clear(); // mutating the caller's list after build() must not reach the detector
    assertEquals("zzzq", detector.detect("zzzq").matches().get(0).term());
    assertEquals(List.of(), HinglishProfanity.defaults().detect("zzzq").matches());
  }

  @Test
  void enumsRoundTripThroughJsValues() {
    assertEquals("high", Confidence.HIGH.jsValue());
    assertEquals("low", Confidence.LOW.jsValue());
    assertEquals(
        List.of("exact", "spaced", "leet", "phrase", "vowel-drop"),
        List.of(Method.values()).stream().map(Method::jsValue).toList());
    assertEquals(
        List.of("en", "hi-deva", "hi-latin"), List.of(Lang.values()).stream().map(Lang::jsValue).toList());

    for (Confidence c : Confidence.values()) assertSame(c, Confidence.fromJsValue(c.jsValue()));
    for (Method m : Method.values()) assertSame(m, Method.fromJsValue(m.jsValue()));
    for (Lang l : Lang.values()) assertSame(l, Lang.fromJsValue(l.jsValue()));

    assertThrows(IllegalArgumentException.class, () -> Method.fromJsValue("VOWEL_DROP"));
    assertThrows(IllegalArgumentException.class, () -> Confidence.fromJsValue("HIGH"));
  }

  /** UTF-16 offsets, the unit String.substring uses: the emoji is two code units. */
  @Test
  void offsetsAreUtf16CodeUnits() {
    String input = "hi 😀 fuck";
    Match match = HinglishProfanity.defaults().detect(input).matches().get(0);
    assertEquals(6, match.index());
    assertEquals(10, match.endIndex());
    assertEquals("fuck", input.substring(match.index(), match.endIndex()));
  }

  /**
   * JS returns NaN when a weight is NaN (KNOWN_ISSUES.md, P3). JSON cannot
   * carry NaN, so the parity fixture cannot cover it; this does.
   */
  @Test
  void nanWeightGivesNanScoreAsInJavaScript() {
    Detector detector = HinglishProfanity.builder().weights(Map.of(3, Double.NaN)).build();
    assertTrue(Double.isNaN(detector.detect("fuck").score()));
    assertEquals(0.0, detector.detect("hello").score());
  }

  @Test
  void entryRejectsSeverityOutsideOneToFour() {
    assertThrows(IllegalArgumentException.class, () -> new Entry("x", 0));
    assertThrows(IllegalArgumentException.class, () -> new Entry("x", 5));
    assertThrows(NullPointerException.class, () -> new Entry(null, 1));
  }
}
