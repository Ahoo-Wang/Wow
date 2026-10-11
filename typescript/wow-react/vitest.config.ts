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
      environment: 'jsdom',
      clearMocks: true,
      restoreMocks: true,
      unstubGlobals: true,
      globals: true,
      coverage: {
        include: ['src/**/*.ts'],
        exclude: [...configDefaults.exclude],
        // Held below what the suite measured on 2026-10-10 (statements 98.68,
        // branches 96.82, functions 98.41, lines 99.41). The package is small,
        // so each gap leaves room for one more uncovered statement, branch,
        // function or line: a coverage drop fails CI, a behaviour-preserving
        // refactor does not. Raise them as coverage rises, but never to 100.
        thresholds: {
          statements: 98,
          branches: 96,
          functions: 96.5,
          lines: 98.5,
        },
      },
    },
  }),
);
