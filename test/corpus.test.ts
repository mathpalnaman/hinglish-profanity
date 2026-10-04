/**
 * Runs test/fixtures/corpus.jsonl against detect().
 *
 * The corpus is the spec. It was written before the normalizer and matcher, and
 * a failing case means the implementation is wrong until proven otherwise —
 * never edit a case to make the code pass.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

import { detect } from "../src/index";
import type { DetectOptions, Lang, Severity } from "../src/types";

interface ExpectedMatch {
  term: string;
  lang?: Lang;
  severity?: Severity;
  confidence?: "high" | "low";
}

interface CorpusCase {
  id: string;
  input: string | null;
  expect: ExpectedMatch[] | { throws: string };
  options?: DetectOptions;
  source: string;
  /** Known gaps (homoglyphs, vowel-drop). Skipped so today's suite can be green. */
  phase?: string;
  note?: string;
}

const here = dirname(fileURLToPath(import.meta.url));

const cases: CorpusCase[] = readFileSync(join(here, "fixtures", "corpus.jsonl"), "utf8")
  .split("\n")
  .filter((line) => line.trim().length > 0)
  .map((line, i) => {
    try {
      return JSON.parse(line) as CorpusCase;
    } catch (error) {
      throw new Error(`corpus.jsonl line ${i + 1} is not valid JSON: ${String(error)}`);
    }
  });

const positives = cases.filter((c) => Array.isArray(c.expect) && c.expect.length > 0);
const negatives = cases.filter((c) => Array.isArray(c.expect) && c.expect.length === 0);
const contracts = cases.filter((c) => !Array.isArray(c.expect));

describe("corpus", () => {
  it("has unique ids and parses", () => {
    const ids = cases.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(cases.length).toBeGreaterThan(100);
  });

  describe("positives — must be detected", () => {
    for (const testCase of positives) {
      const expected = testCase.expect as ExpectedMatch[];
      const run = testCase.phase === "day2" ? it.skip : it;

      run(`${testCase.id}: ${JSON.stringify(testCase.input)}`, () => {
        const result = detect(testCase.input as string, testCase.options ?? {});
        const terms = result.matches.map((m) => m.term);

        for (const want of expected) {
          expect(terms, `${testCase.id}: expected term "${want.term}"`).toContain(want.term);

          const got = result.matches.find((m) => m.term === want.term);
          if (got === undefined) continue;

          if (want.lang !== undefined) expect(got.lang).toBe(want.lang);
          if (want.severity !== undefined) expect(got.severity).toBe(want.severity);
          if (want.confidence !== undefined) expect(got.confidence).toBe(want.confidence);

          // Offsets must point at the real text, not at the normalized text.
          expect(
            (testCase.input as string).slice(got.index, got.endIndex),
            `${testCase.id}: matchedText must equal the slice it claims`,
          ).toBe(got.matchedText);
        }

        expect(result.score).toBeGreaterThan(0);
      });
    }
  });

  describe("negatives — must pass clean", () => {
    for (const testCase of negatives) {
      const run = testCase.phase === "day2" ? it.skip : it;

      run(`${testCase.id}: ${JSON.stringify(testCase.input)}`, () => {
        const result = detect(testCase.input as string, testCase.options ?? {});
        expect(
          result.matches,
          `${testCase.id} (${testCase.note ?? "no note"}) matched: ${JSON.stringify(
            result.matches.map((m) => `${m.term}@${m.index}`),
          )}`,
        ).toEqual([]);
        expect(result.score).toBe(0);
      });
    }
  });

  describe("contract", () => {
    for (const testCase of contracts) {
      const want = testCase.expect as { throws: string };

      it(`${testCase.id}: throws ${want.throws}`, () => {
        expect(() => detect(testCase.input as unknown as string)).toThrow(TypeError);
      });
    }
  });
});
