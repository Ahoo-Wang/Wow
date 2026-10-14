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

import { defineConfig } from "@playwright/test";

/**
 * The UI end to end (`pnpm test:e2e`): e2e/globalSetup.ts starts the five
 * eShop services in one JVM on the memory backend, every port bound to 0 and
 * read back, and the dev server with a proxy to each; the suite drives the
 * UI in Chromium. `ESHOP_WEB_BROWSER_CHANNEL=chrome` runs it in the installed
 * Chrome instead of Playwright's own build.
 */
export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/globalSetup.ts",
  // One shop, written in order: no parallel workers, and no retries (a
  // retry that passes hides the failure it retried).
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: Boolean(process.env.CI),
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    browserName: "chromium",
    channel: process.env.ESHOP_WEB_BROWSER_CHANNEL,
    viewport: { width: 1280, height: 900 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
});
