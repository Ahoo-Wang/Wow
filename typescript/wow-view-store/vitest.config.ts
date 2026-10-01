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
        include: ['src/**/*.ts'],
        exclude: [...configDefaults.exclude],
        // Held a point or two below what the suite measured on 2026-09-30
        // (statements 96.88, branches 92.63, functions 98.41, lines 98.52): a
        // coverage drop fails CI, a behaviour-preserving refactor does not.
        thresholds: {
          statements: 95,
          branches: 90,
          functions: 96,
          lines: 97,
        },
      },
    },
  }),
);
