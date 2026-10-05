/**
 * Build-time constants shared by tsup, vitest and the parity generator.
 *
 * WORDLIST_HASH is the first 12 hex chars of SHA-256 over the raw bytes of the
 * three wordlists, concatenated in sorted filename order. The Java build
 * computes the same value from the same files; `.gitattributes` pins them to
 * LF so every checkout hashes identically.
 */

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

export const WORDLIST_FILES = ["en.json", "hi-deva.json", "hi-latin.json"];

export function stamp() {
  const hash = createHash("sha256");
  for (const file of WORDLIST_FILES) hash.update(readFileSync(join(root, "src", "wordlists", file)));

  const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
  return { VERSION: pkg.version, WORDLIST_HASH: hash.digest("hex").slice(0, 12) };
}

/** The `define` map both bundlers take: identifiers replaced with literals. */
export function defines() {
  const { VERSION, WORDLIST_HASH } = stamp();
  return {
    __VERSION__: JSON.stringify(VERSION),
    __WORDLIST_HASH__: JSON.stringify(WORDLIST_HASH),
  };
}
