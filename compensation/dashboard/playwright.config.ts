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

import { defineConfig, type PlaywrightTestConfig } from "@playwright/test";

const port = 4174;

/**
 * A running compensation server that serves this build (`dist/`) and the API
 * from one origin, e.g. `http://127.0.0.1:18083`. With it, only the
 * real-server smoke runs, against that server (RELEASING.md §C′ step 5);
 * without it, only the stubbed suite runs, against `vite preview`.
 */
const realServer = process.env.WOW_COMPENSATION_URL;

const realServerConfig: PlaywrightTestConfig = {
  testDir: "./e2e/real-server",
  // It writes to the server, so it runs once, in order, and never retries:
  // a failure there is the finding.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: realServer,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "real-server",
      use: { browserName: "chromium", viewport: { width: 1440, height: 900 } },
    },
  ],
};

const stubbedConfig: PlaywrightTestConfig = {
  testDir: "./e2e",
  testIgnore: "real-server/**",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    screenshot: "only-on-failure",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "desktop-chromium",
      use: { browserName: "chromium", viewport: { width: 1440, height: 900 } },
    },
    {
      name: "mobile-chromium",
      use: {
        browserName: "chromium",
        hasTouch: true,
        isMobile: true,
        viewport: { width: 390, height: 844 },
      },
    },
  ],
  webServer: {
    command: `pnpm preview --host 127.0.0.1 --port ${port}`,
    reuseExistingServer: !process.env.CI,
    url: `http://127.0.0.1:${port}`,
  },
};

export default defineConfig(realServer ? realServerConfig : stubbedConfig);
