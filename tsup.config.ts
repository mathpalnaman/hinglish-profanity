import { defineConfig } from "tsup";

export default defineConfig({
  /* One entry point. Everything else is internal. */
  entry: ["src/index.ts"],

  /* Emit ESM (.js) and CommonJS (.cjs) so both import and require work. */
  format: ["esm", "cjs"],
  outExtension: ({ format }) => ({ js: format === "cjs" ? ".cjs" : ".js" }),

  /* Ship type declarations. Without this, TS consumers get `any`. */
  dts: true,

  /* Bundle the wordlist JSON into the output — nothing is read from disk at runtime. */
  bundle: true,
  splitting: false,
  treeshake: true,

  /* Match tsconfig's target so the output needs no runtime polyfills. */
  target: "es2022",
  platform: "neutral",

  /* Readable output: someone evaluating the package will open dist/index.js. */
  minify: false,
  sourcemap: true,

  clean: true,
});
