/**
 * Wordlist self-check.
 *
 * A term that cannot be found when it is the entire input can never match
 * anything, so every entry must detect itself. The Java build runs the same
 * check against the same files.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

import { detect, normalizeTerm, VERSION, WORDLIST_HASH } from "../src/index";
import type { Wordlist } from "../src/types";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");

const lists = ["en.json", "hi-deva.json", "hi-latin.json"].map(
  (file) => JSON.parse(readFileSync(join(root, "src", "wordlists", file), "utf8")) as Wordlist,
);

describe("wordlists", () => {
  for (const list of lists) {
    const entries = [...list.terms, ...(list.phrases ?? [])];

    describe(list.lang, () => {
      for (const entry of entries) {
        it(`${entry.term} detects itself at index 0`, () => {
          const hit = detect(entry.term).matches.find((m) => m.term === entry.term);
          expect(hit, `"${entry.term}" is not reported for its own text`).toBeDefined();
          expect(hit?.index).toBe(0);
          expect(hit?.lang).toBe(list.lang);
        });

        it(`${entry.term} normalizes idempotently`, () => {
          for (const word of entry.term.split(/\s+/)) {
            const once = normalizeTerm(word);
            expect(once.length).toBeGreaterThan(0);
            expect(normalizeTerm(once)).toBe(once);
          }
        });
      }
    });
  }
});

describe("build stamps", () => {
  it("VERSION matches package.json", () => {
    const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8")) as { version: string };
    expect(VERSION).toBe(pkg.version);
  });

  it("WORDLIST_HASH is 12 hex chars and matches the parity fixture", () => {
    expect(WORDLIST_HASH).toMatch(/^[0-9a-f]{12}$/);
    const parity = JSON.parse(readFileSync(join(here, "fixtures", "parity.json"), "utf8")) as {
      wordlistHash: string;
    };
    expect(parity.wordlistHash).toBe(WORDLIST_HASH);
  });
});
