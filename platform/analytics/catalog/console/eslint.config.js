import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { globalIgnores } from "eslint/config";

// The view engine through its public entries only, as the compensation
// dashboard reaches it: the root, /react, /ui, the router adapter and the CSS
// entries; /testing for tests.
const engineEntries = (extra) => ({
  patterns: [
    {
      regex: `^@ahoo-wang/wow-view-engine/(?!(react|ui|react-router${extra}|styles\\.css|themes\\.css|themes/[a-z]+\\.css|shadcn-bridge\\.css)$)`,
      message: "Import the view engine from its public entries.",
    },
  ],
});

export default tseslint.config([
  globalIgnores(["dist", "coverage", "test-results", "playwright-report"]),
  {
    files: ["**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: { globals: globals.browser },
    rules: {
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports" },
      ],
      "no-restricted-imports": ["error", engineEntries("")],
    },
  },
  {
    // The tests, and the fixtures they share (`testing.tsx`).
    files: ["src/**/*.test.{ts,tsx}", "src/**/testing.{ts,tsx}"],
    rules: { "no-restricted-imports": ["error", engineEntries("|testing")] },
  },
  {
    files: ["*.config.ts", "e2e/**/*.ts"],
    languageOptions: { globals: globals.node },
  },
]);
