/**
 * Generates test/fixtures/parity.json: what the JS build returns for a fixed
 * set of inputs and option combinations. The Java port must reproduce every
 * result exactly (java/src/test/.../ParityTest.java).
 *
 * Runs against dist/, not src/, so it records what is actually published.
 * `npm run gen:parity` builds first.
 *
 * Node version matters: \p{L}, lowercasing and NFKD follow the Unicode version
 * of the runtime. Generate with the version in .nvmrc, as CI does.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = join(root, "test", "fixtures");

const { detect, WORDLIST_HASH } = await import(pathToFileURL(join(root, "dist", "index.js")).href);

const lines = (file) =>
  readFileSync(join(fixtures, file), "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

const corpus = lines("corpus.jsonl").map((line) => JSON.parse(line));
const extra = lines("parity-inputs.txt")
  .filter((line) => !line.startsWith("#"))
  .map((line) => JSON.parse(line));

/* Inputs: every corpus string, then the extras, de-duplicated in that order. */
const inputs = [
  ...new Set([...corpus.map((c) => c.input).filter((i) => typeof i === "string"), ...extra]),
];

const EXTRA_WORDS = [
  { term: "bakwas", severity: 1 },
  { term: "bad word", severity: 2 },
  { term: "zzz", severity: 1, wholeTokenOnly: true },
  { term: "astro", severity: 2, ambiguous: true },
  { term: "बकवास", severity: 2, ambiguous: true },
  { term: "आदमी", severity: 1, wholeTokenOnly: true },
];
const ALLOWLIST = ["dr lund", "mother", "niki lauda", "MC Stan", "लंडन", "piece of"];

/* Option combinations run against every input. */
const optionSets = [
  {},
  { langs: ["en"] },
  { langs: ["hi-latin"] },
  { langs: ["hi-deva"] },
  { langs: ["en", "hi-latin"] },
  { langs: ["en", "hi-deva"] },
  { langs: ["hi-latin", "hi-deva"] },
  { langs: ["en", "hi-latin", "hi-deva"] },
  { langs: [] },
  { vowelDrop: true },
  { allowlist: ALLOWLIST },
  { extraWords: EXTRA_WORDS },
  { langs: [], extraWords: EXTRA_WORDS },
  { weights: { 1: 0, 2: 2.5, 3: 4, 4: 10 } },
  { weights: { 3: 0.335 } },
  {
    langs: ["en", "hi-latin"],
    vowelDrop: true,
    allowlist: ALLOWLIST,
    extraWords: EXTRA_WORDS,
    weights: { 1: 0.5, 4: 3 },
  },
];

/*
 * Layout: `cleaned` does not depend on options, and most results are empty, so
 * results are grouped per input instead of repeating the input 16 times. That
 * keeps a generated file that is committed and diffed to a fraction of the
 * size. runs[i] is the result for optionSets[i], minus `cleaned`.
 */
const perInput = inputs.map((input) => {
  const results = optionSets.map((options) => detect(input, options));
  const cleaned = results[0].cleaned;
  for (const r of results) {
    if (r.cleaned !== cleaned) throw new Error(`cleaned varies with options for ${JSON.stringify(input)}`);
  }
  return { input, cleaned, runs: results.map(({ score, matches }) => ({ score, matches })) };
});

/* Corpus cases that carry their own options (per-case allowlists). */
const withOptions = corpus
  .filter((c) => typeof c.input === "string" && c.options !== undefined)
  .map((c) => ({ input: c.input, options: c.options, result: detect(c.input, c.options) }));

/* One entry per line: the file is generated, but its diffs should be readable. */
const rows = (items) => items.map((item) => JSON.stringify(item)).join(",\n");
const body =
  `{\n"wordlistHash": ${JSON.stringify(WORDLIST_HASH)},\n` +
  `"optionSets": [\n${rows(optionSets)}\n],\n` +
  `"inputs": [\n${rows(perInput)}\n],\n` +
  `"cases": [\n${rows(withOptions)}\n]\n}\n`;

writeFileSync(join(fixtures, "parity.json"), body);
console.log(
  `parity.json: ${perInput.length * optionSets.length + withOptions.length} cases ` +
    `(${perInput.length} inputs x ${optionSets.length} option sets + ${withOptions.length} corpus cases with options)`,
);
