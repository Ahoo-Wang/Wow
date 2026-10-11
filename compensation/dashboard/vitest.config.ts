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
      exclude: [...configDefaults.exclude, "e2e/**"],
      reporters: ["verbose"],
      environment: "jsdom",
      setupFiles: ["./src/setupTests.ts"],
      globals: true,
      coverage: {
        provider: "v8",
        include: ["src/**"],
        exclude: [
          ...(configDefaults.coverage.exclude ?? []),
          "src/generated/**",
        ],
        // A point under what the suite reaches (2026-10-11: 62.3 / 87.5 /
        // 87.5 / 75.9), so coverage only moves up (R2-103); raise them as
        // it does.
        thresholds: {
          branches: 61,
          functions: 86,
          lines: 86,
          statements: 74,
        },
      },
    },
  }),
);
