import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import reactCompiler from "eslint-plugin-react-compiler";
import tseslint from "typescript-eslint";
import { globalIgnores } from "eslint/config";

export default tseslint.config([
  globalIgnores(["dist", "coverage"]),
  {
    files: ["src/**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      reactCompiler.configs.recommended,
    ],
    rules: {
      ...reactHooks.configs["recommended-latest"].rules,
      ...reactRefresh.configs.vite.rules,
      "spaced-comment": ["error", "always", { markers: ["/"] }],
      "no-warning-comments": [
        "error",
        {
          terms: ["todo", "fixme", "hack", "stopship"],
          location: "anywhere",
        },
      ],
      "@typescript-eslint/consistent-type-imports": [
        "error",
        {
          prefer: "type-imports",
        },
      ],
      // The view engine through its public face only (rebuild proposal,
      // criterion 7): the root, /react, /ui, the router adapter and the CSS
      // entries; never its internals, and never its class names — its look
      // is reached through `--fve-*` variables alone, bar `fve-tokens`, the
      // boundary its theme is worn through on the host's own chrome (D17-10,
      // host-integration.md 4.1). /testing is for tests (below).
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              regex:
                "^@ahoo-wang/wow-view-engine/(?!(react|ui|react-router|styles\\.css|themes\\.css|themes/[a-z]+\\.css|shadcn-bridge\\.css)$)",
              message:
                "Import the view engine from its public entries: the root, /react, /ui, /react-router or a CSS entry.",
            },
          ],
        },
      ],
      "no-restricted-syntax": [
        "error",
        {
          selector: "Literal[value=/(^|[\\s.])fve-(?!tokens\\b)/]",
          message:
            "Do not reach into the view engine's class names; style it through --fve-* variables.",
        },
        {
          selector: "TemplateElement[value.raw=/(^|[\\s.])fve-(?!tokens\\b)/]",
          message:
            "Do not reach into the view engine's class names; style it through --fve-* variables.",
        },
      ],
    },
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    // A test may also take /testing: the in-memory source and `admit`.
    files: ["src/**/*.test.{ts,tsx}"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              regex:
                "^@ahoo-wang/wow-view-engine/(?!(react|ui|react-router|testing|styles\\.css|themes\\.css|themes/[a-z]+\\.css|shadcn-bridge\\.css)$)",
              message:
                "Import the view engine from its public entries: the root, /react, /ui, /react-router, /testing or a CSS entry.",
            },
          ],
        },
      ],
    },
  },
  {
    // wow-generator maps free-form OpenAPI schemas (`{}`, `type: object`) and
    // the decorator `attributes` parameter to `any`; every other rule applies.
    files: ["src/generated/**/*.ts"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
]);
