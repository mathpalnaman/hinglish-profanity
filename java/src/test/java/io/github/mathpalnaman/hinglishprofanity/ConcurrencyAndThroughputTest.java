package io.github.mathpalnaman.hinglishprofanity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyAndThroughputTest {

  /** Every string input in the corpus: the realistic mix of clean and abusive reviews. */
  private static List<String> corpusInputs() {
    List<String> inputs = new ArrayList<>();
    for (JsonNode c : Fixtures.corpus()) {
      if (c.get("input").isTextual()) inputs.add(c.get("input").asText());
    }
    return inputs;
  }

  /** 8 threads x 10,000 calls on one shared Detector must all see the single-threaded results. */
  @Test
  void sharedDetectorIsThreadSafe() throws Exception {
    int threads = 8;
    int callsPerThread = 10_000;

    Detector detector = HinglishProfanity.builder().vowelDrop(true).allowlist(List.of("dr lund")).build();
    List<String> inputs = corpusInputs();
    List<Result> expected = new ArrayList<>();
    for (String input : inputs) expected.add(detector.detect(input));

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int offset = t * 17; // each thread walks the inputs from a different start
        futures.add(
            pool.submit(
                () -> {
                  int mismatches = 0;
                  for (int i = 0; i < callsPerThread; i++) {
                    int at = (offset + i) % inputs.size();
                    if (!detector.detect(inputs.get(at)).equals(expected.get(at))) mismatches++;
                  }
                  return mismatches;
                }));
      }
      int mismatches = 0;
      for (Future<Integer> future : futures) mismatches += future.get(120, TimeUnit.SECONDS);
      assertEquals(0, mismatches, "results differed under concurrency");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Reports single-thread throughput over the corpus inputs. It prints and
   * never fails: a number from a shared CI runner is not a pass/fail signal.
   */
  @Test
  void reportThroughput() {
    Detector detector = HinglishProfanity.defaults();
    List<String> inputs = corpusInputs();

    long sink = 0;
    long warmupUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < warmupUntil) {
      for (String input : inputs) sink += detector.detect(input).matches().size();
    }

    long calls = 0;
    long start = System.nanoTime();
    long until = start + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < until) {
      for (String input : inputs) sink += detector.detect(input).matches().size();
      calls += inputs.size();
    }
    double seconds = (System.nanoTime() - start) / 1e9;

    System.out.printf(
        "hinglish-profanity throughput: %,.0f texts/sec (single thread, %d corpus inputs, sink %d)%n",
        calls / seconds, inputs.size(), sink);
  }
}
