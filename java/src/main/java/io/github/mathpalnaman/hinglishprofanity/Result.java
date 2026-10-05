package io.github.mathpalnaman.hinglishprofanity;

import java.util.List;

/**
 * Result of {@link Detector#detect}. Evidence plus a score. Never a boolean.
 *
 * @param score sum of per-match weights. NOT a probability and NOT a
 *     confidence; there is no default threshold.
 * @param matches every match found, in order of appearance. Unmodifiable.
 * @param cleaned the fully normalized form of the input, for debugging
 */
public record Result(double score, List<Match> matches, String cleaned) {

  public Result {
    matches = List.copyOf(matches);
  }
}
