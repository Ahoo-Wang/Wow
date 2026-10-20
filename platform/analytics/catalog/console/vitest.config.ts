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

import { configDefaults, defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config.ts";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // The end-to-end suite is Playwright's, against the real catalog server.
      exclude: [...configDefaults.exclude, "e2e/**"],
      environment: "jsdom",
      globals: true,
      setupFiles: ["./src/setupTests.ts"],
      // The workbench's component tests and the generator's run each take
      // seconds on a busy runner: more than the default 5 s allows.
      testTimeout: 15_000,
      coverage: {
        provider: "v8",
        include: ["src/**"],
        exclude: [
          ...(configDefaults.coverage.exclude ?? []),
          "src/generated/**",
          "src/components/ui/**",
        ],
        // A point under what the suite reaches (2026-10-20, 7.2c: 91.9 /
        // 87.3 / 90.9 / 93.5), so coverage only moves up; raise them as it
        // does.
        thresholds: {
          statements: 90,
          branches: 86,
          functions: 89,
          lines: 92,
        },
      },
    },
  }),
);
