import { defineConfig } from "vitest/config";

import { defines } from "./scripts/stamp.mjs";

export default defineConfig({
  /* Tests import src/ directly, so they need the same constants tsup injects. */
  define: defines(),
});
