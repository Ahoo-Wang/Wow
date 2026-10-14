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

const port = process.env.OPS_E2E_PORT ?? "18188";

/**
 * A running ops server that serves this build and the ledger simulator
 * (`e2e/start-ops-server.mjs` says what it needs), e.g.
 * `http://127.0.0.1:18188`. Without it, the suite starts one itself.
 */
const given = process.env.WOW_OPS_URL;

/**
 * The console end to end against the real ops server, on its memory
 * backend: `pnpm build`, then `pnpm test:e2e`. The server writes, so the
 * suite runs once, in order, and never retries: a failure is the finding.
 * `OPS_E2E_CHANNEL=chrome` runs it in the installed Chrome instead of
 * Playwright's own Chromium.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  timeout: 60_000,
  use: {
    baseURL: given ?? `http://127.0.0.1:${port}`,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        browserName: "chromium",
        viewport: { width: 1440, height: 900 },
        ...(process.env.OPS_E2E_CHANNEL
          ? { channel: process.env.OPS_E2E_CHANNEL }
          : {}),
      },
    },
  ],
  ...(given
    ? {}
    : {
        webServer: {
          command: "node e2e/start-ops-server.mjs",
          url: `http://127.0.0.1:${port}/index.html`,
          reuseExistingServer: false,
          timeout: 120_000,
          stdout: "ignore",
          stderr: "pipe",
          env: { OPS_E2E_PORT: port },
        },
      }),
});
