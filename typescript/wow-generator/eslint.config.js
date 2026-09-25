/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import js from '@eslint/js';
import { createNodeResolver, importX } from 'eslint-plugin-import-x';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * A zone keeping a layer from importing any module of the package outside
 * itself and the layers it names.
 */
function leaf(layer, allowed) {
  return {
    target: `./src/${layer}`,
    from: './src',
    except: [`./${layer}`, ...allowed.map(name => `./${name}`)],
    message: `${layer}/ may import only ${allowed.length ? allowed.join('/, ') + '/' : 'itself'}.`,
  };
}

export default tseslint.config(
  {
    ignores: [
      '**/dist/**',
      '**/**.test.ts',
      '**/node_modules/**',
      '**/expected/**',
      '**/test-output/**',
    ],
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
      '@typescript-eslint/consistent-type-imports': [
        'error',
        {
          prefer: 'type-imports',
        },
      ],
    },
  },
  {
    // Dependencies point one way (docs/design/architecture.md). no-cycle
    // catches a value import that closes a loop; it skips `import type`, so
    // the zones below hold the layers type imports included: each layer
    // imports only the ones below it, and only the entries reach the pipeline
    // and the CLI. analysis/ decides what a document generates without
    // ts-morph; emitters/ write it through the emit/ kit, which never reads
    // the generation model.
    files: ['src/**/*.ts'],
    plugins: { 'import-x': importX },
    settings: {
      'import-x/extensions': ['.ts'],
      'import-x/parsers': { '@typescript-eslint/parser': ['.ts'] },
      'import-x/resolver-next': [
        createNodeResolver({ extensions: ['.ts', '.json'] }),
      ],
    },
    rules: {
      // Generated output must not depend on the machine's locale.
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.property.name='localeCompare']",
          message:
            'localeCompare follows the default locale; sort names with compareNames from naming/order.ts.',
        },
      ],
      'import-x/no-cycle': ['error', { ignoreExternal: true }],
      'import-x/no-restricted-paths': [
        'error',
        {
          basePath: import.meta.dirname,
          zones: [
            leaf('api', []),
            leaf('naming', ['api']),
            leaf('openapi', ['api', 'naming']),
            leaf('input', ['api', 'naming', 'openapi']),
            leaf('output', ['api', 'naming']),
            leaf('finalize', ['api']),
            leaf('emit', ['api', 'naming']),
            leaf('types', ['api', 'naming', 'openapi', 'emit']),
            leaf('wow', ['api', 'openapi']),
            leaf('analysis', ['api', 'naming', 'openapi', 'wow']),
            leaf('emitters', [
              'api',
              'naming',
              'openapi',
              'wow',
              'analysis',
              'types',
              'emit',
            ]),
            {
              target: './src/!(cli.ts|index.ts|cli)/**',
              from: './src/cli',
              message: 'Only the CLI entry runs the CLI.',
            },
            {
              target: './src/!(cli.ts|index.ts|cli|pipeline)/**',
              from: './src/pipeline',
              message: 'Only the entries and the CLI run the pipeline.',
            },
          ],
        },
      ],
    },
  },
  {
    // Reading and analysing a document never touches ts-morph: only emitting,
    // finishing and writing the output do.
    files: [
      'src/api/**/*.ts',
      'src/input/**/*.ts',
      'src/naming/**/*.ts',
      'src/openapi/**/*.ts',
      'src/wow/**/*.ts',
      'src/analysis/**/*.ts',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'ts-morph',
              message:
                'Reading and analysing a document is pure data; leave ts-morph to emitters/, finalize/ and output/.',
            },
          ],
        },
      ],
    },
  },
);
