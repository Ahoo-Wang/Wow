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

import { defineConfig } from 'vitest/config';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';
import babel from '@rolldown/plugin-babel';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    ...(mode === 'compiled'
      ? [babel({ presets: [reactCompilerPreset()] })]
      : []),
  ],
  resolve: {
    alias: [
      {
        find: '@',
        replacement: fileURLToPath(new URL('./src', import.meta.url)),
      },
    ],
  },
  test: {
    environment: 'jsdom',
    // The browser APIs jsdom lacks and `@dnd-kit/dom` reaches for as it is
    // imported; see `test/setup.ts`.
    setupFiles: ['test/setup.ts'],
    // Whole-flow UI tests drive a board through user-event on jsdom: ~2 s
    // locally, past vitest's 5 s default on a loaded CI runner. A test that
    // really hangs still fails, at 15 s.
    testTimeout: 15_000,
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      // `src/ui/components` and `src/ui/lib` are vendored from the shadcn
      // registry and updated with `shadcn add --diff`, so they are upstream's
      // to test; what this package owns is the composition above them.
      exclude: ['src/ui/components/**', 'src/ui/lib/**', 'src/styles.ts'],
      // A shard runs a third of the suite, so its coverage is a third of the
      // truth; CI holds the thresholds when `--merge-reports` joins the shards.
      thresholds: process.argv.some(arg => arg.startsWith('--shard'))
        ? undefined
        : {
            statements: 95,
            branches: 91,
            functions: 97,
            lines: 96,
          },
    },
  },
}));
