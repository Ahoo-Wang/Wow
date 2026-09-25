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
 * The dependency directions of docs/design/architecture.md §2.2, one
 * block per layer of src/. An edge the section does not draw is an error.
 * The entries (src/index.ts, src/dsl.ts, src/legacy/index.ts) only re-export
 * and may reach every layer. verify-package.mjs still checks the built /dsl
 * entry as a second line.
 */
function layerBoundaries() {
  const dir = name => `(^|/)${name}/`;
  const layer = {
    client: dir('client'),
    transport: dir('transport'),
    dsl: dir('dsl'),
    model: dir('model'),
    error: dir('error'),
    legacy: dir('legacy'),
  };
  const packages = {
    fetcher: '^@ahoo-wang/fetcher',
    decorator: '^@ahoo-wang/fetcher-decorator',
    eventstream: '^@ahoo-wang/fetcher-eventstream',
    reflect: '^reflect-metadata',
  };
  const deny = (from, target, extra = {}) => ({
    regex: target,
    message: `${from}/ must not import this (see docs/design/architecture.md §2.2).`,
    ...extra,
  });
  const rule = patterns => ({
    '@typescript-eslint/no-restricted-imports': ['error', { patterns }],
  });
  /** No HTTP code: no client, no transport, no fetcher package. */
  const pure = from => [
    deny(from, layer.client),
    deny(from, layer.transport),
    deny(from, layer.legacy),
    deny(from, packages.fetcher),
    deny(from, packages.reflect),
  ];
  return [
    {
      files: ['src/dsl/**/*.ts'],
      rules: rule([...pure('dsl'), deny('dsl', layer.error)]),
    },
    {
      // A command result carries an ErrorInfo: model/ may name error/'s
      // types, never its runtime.
      files: ['src/model/**/*.ts'],
      rules: rule([
        ...pure('model'),
        deny('model', layer.dsl),
        deny('model', layer.error, { allowTypeImports: true }),
      ]),
    },
    {
      files: ['src/error/**/*.ts'],
      rules: rule([
        ...pure('error'),
        deny('error', layer.dsl),
        deny('error', layer.model),
      ]),
    },
    {
      files: ['src/transport/**/*.ts'],
      rules: rule([
        deny('transport', layer.client),
        deny('transport', layer.dsl),
        deny('transport', layer.legacy),
        deny('transport', packages.decorator),
        deny('transport', packages.reflect),
      ]),
    },
    {
      // Only transport/ imports fetcher-eventstream: the clients answer
      // rows, so they name no server-sent event type (A3). Only
      // client/query/requests.ts reaches into the deprecated /legacy.
      files: ['src/client/**/*.ts'],
      ignores: ['src/client/query/requests.ts'],
      rules: rule([
        deny('client', layer.legacy),
        deny('client', packages.eventstream),
      ]),
    },
    {
      files: ['src/client/query/requests.ts'],
      rules: rule([
        deny('client', layer.legacy, { allowTypeImports: true }),
        deny('client', packages.eventstream),
      ]),
    },
    {
      files: ['src/legacy/**/*.ts'],
      ignores: ['src/legacy/index.ts'],
      rules: rule([
        deny('legacy', layer.client),
        deny('legacy', layer.transport),
        deny('legacy', layer.model),
        deny('legacy', layer.error),
        deny('legacy', packages.fetcher),
        deny('legacy', packages.reflect),
      ]),
    },
  ];
}
