import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['dist', 'node_modules'],
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-expressions': 'warn',
      '@typescript-eslint/no-unused-vars': 'warn',
      '@typescript-eslint/no-unsafe-function-type': 'warn',
      '@typescript-eslint/consistent-type-imports': [
        'error',
        {
          prefer: 'type-imports',
        },
      ],
    },
  },
  {
    // A library does not write to its host's console; report through errors
    // or return values instead. Tests may log.
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'no-console': 'error',
    },
  },
  ...layerBoundaries(),
);

/**
 * The dependency directions of src/, one block per layer. An edge not drawn
 * here is an error. The client modules at the top of src/ (commands, http,
 * queries, streams, …) build on the DSL's types only, the DSL loads no HTTP
 * code and nothing of the client, and testing/ loads no test runner. The
 * entries (src/index.ts, src/dsl.ts, src/testing.ts) only re-export.
 * verify-package.mjs still checks the built /dsl and /testing entries as a
 * second line.
 */
function layerBoundaries() {
  const layer = {
    dsl: '(^|/)dsl(/|\\.js$|$)',
    // A module of the client, from inside the DSL: `../http.js`, `../../index.js`.
    client:
      '^(\\.\\./)+(commands|developmentCaller|dsl|http|index|manifest|problem|queries|state|streams|wait|wire)\\.js$',
  };
  const packages = {
    fetcher: '^@ahoo-wang/fetcher',
    decorator: '^@ahoo-wang/fetcher-decorator',
    reflect: '^reflect-metadata',
    runner: '^(vitest|@vitest/|jest|@jest/|mocha)',
  };
  const deny = (from, target, extra = {}) => ({
    regex: target,
    message: `${from} must not import this (see eslint.config.js, layerBoundaries).`,
    ...extra,
  });
  const rule = patterns => ({
    '@typescript-eslint/no-restricted-imports': ['error', { patterns }],
  });
  return [
    {
      files: ['src/dsl/**/*.ts'],
      rules: rule([
        deny('dsl/', layer.client),
        deny('dsl/', packages.fetcher),
        deny('dsl/', packages.reflect),
      ]),
    },
    {
      // The /testing entry is run by any test runner, a story or a tool: it
      // loads none.
      files: ['src/testing/**/*.ts'],
      rules: rule([
        deny('testing/', packages.runner),
        deny('testing/', packages.decorator),
        deny('testing/', packages.reflect),
      ]),
    },
    {
      files: ['src/*.ts'],
      ignores: ['src/index.ts', 'src/dsl.ts', 'src/testing.ts'],
      rules: rule([
        deny('The client', layer.dsl, { allowTypeImports: true }),
        deny('The client', packages.decorator),
        deny('The client', packages.reflect),
      ]),
    },
  ];
}
