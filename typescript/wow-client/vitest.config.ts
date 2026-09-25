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

import { configDefaults, defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      clearMocks: true,
      restoreMocks: true,
      unstubGlobals: true,
      coverage: {
        // Only the shipped source; test helpers are not the product.
        include: ['src/**/*.ts'],
        exclude: [
          ...configDefaults.exclude,
          '**/**.stories.tsx',
          'test/fixtures/**',
          'test/clients/fetchStub.ts',
          'scripts/**',
        ],
        // Held a point or two below what the suite measured on 2026-09-24
        // (statements 100, branches 99.37, functions 100, lines 100): a
        // coverage drop fails CI, a behaviour-preserving refactor does not.
        // Raise them as coverage rises, but never to 100.
        thresholds: {
          statements: 98,
          branches: 97,
          functions: 98,
          lines: 98,
        },
      },
    },
  }),
);
