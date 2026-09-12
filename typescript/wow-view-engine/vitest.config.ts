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
      // Development adapters use the public contract; unit tests exercise its current source.
      {
        find: /^@ahoo-wang\/fetcher-view-engine$/,
        replacement: fileURLToPath(new URL('./src/index.ts', import.meta.url)),
      },
    ],
  },
  test: {
    environment: 'jsdom',
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      thresholds: {
        statements: 95,
        branches: 91,
        functions: 97,
        lines: 96,
      },
    },
  },
}));
