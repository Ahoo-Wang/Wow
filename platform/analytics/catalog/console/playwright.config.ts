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
import { COSEC_SECRET } from "./e2e/cosec.ts";

const port = process.env.CATALOG_E2E_PORT ?? "18187";

/** The catalog server in its `cosec` profile, which `cosec.spec.ts` signs in to. */
const cosecPort = process.env.CATALOG_E2E_COSEC_PORT ?? "18186";

const browser = {
  browserName: "chromium" as const,
  viewport: { width: 1440, height: 900 },
  ...(process.env.CATALOG_E2E_CHANNEL
    ? { channel: process.env.CATALOG_E2E_CHANNEL }
    : {}),
};

function server(serverPort: string, env: Record<string, string> = {}) {
  return {
    command: "node e2e/start-catalog-server.mjs",
    url: `http://127.0.0.1:${serverPort}/index.html`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: "ignore" as const,
    stderr: "pipe" as const,
    env: { CATALOG_E2E_PORT: serverPort, ...env },
  };
}

/**
 * A running catalog server that serves this build and the warehouse
 * simulator (`e2e/start-catalog-server.mjs` says what it needs), e.g.
 * `http://127.0.0.1:18187`. Without it, the suite starts one itself.
 */
const given = process.env.WOW_CATALOG_URL;

/**
 * The console end to end against the real analytics catalog server, on its memory
 * backend: `pnpm build`, then `pnpm test:e2e`. The server writes, so the
 * suite runs once, in order, and never retries: a failure is the finding.
 * `CATALOG_E2E_CHANNEL=chrome` runs it in the installed Chrome instead of
 * Playwright's own Chromium. A second server runs in the `cosec` profile, as a
 * deployment does, for `cosec.spec.ts`: the data owner signs in with a CoSec
 * token (not against a server given by `WOW_CATALOG_URL`).
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
      testIgnore: /cosec\.spec\.ts/,
      use: browser,
    },
    ...(given
      ? []
      : [
          {
            name: "cosec",
            testMatch: /cosec\.spec\.ts/,
            use: { ...browser, baseURL: `http://127.0.0.1:${cosecPort}` },
          },
        ]),
  ],
  ...(given
    ? {}
    : {
        webServer: [
          server(port),
          server(cosecPort, { CATALOG_E2E_COSEC_SECRET: COSEC_SECRET }),
        ],
      }),
});
