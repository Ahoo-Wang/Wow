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

import { expect, test, type Page } from "@playwright/test";
import { accessToken } from "./cosec.ts";
import { CONNECTION, DATABASE, putTable } from "./warehouse.ts";
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

/*
 * The console against the catalog server in its production shape, the
 * `cosec` profile, behind the CoSec gateway (here its stand-in,
 * `e2e/cosec-gateway.mjs`): the data owner signs in with a CoSec access
 * token, which the gateway verifies and forwards as the identity the service
 * reads the caller from. No identity header a client sends is trusted.
 */

const EDITOR = accessToken("eve", ["analytics.catalog.editor"]);

/** The console in English, signed in as nobody. */
async function open(page: Page, path = "/#/sources") {
  await page.addInitScript(() =>
    localStorage.setItem("wow-analytics-catalog-console.locale", "en"),
  );
  await page.goto(path);
}

async function signIn(page: Page, token: string) {
  await expect(page.getByText("Sign in to read the catalog")).toBeVisible();
  await page.getByLabel("Access token").fill(token);
  await page.getByRole("button", { name: "Sign in" }).click();
}

test.describe.configure({ mode: "serial" });

test("a request without a token is answered 401, and no identity header is trusted", async ({
  request,
}) => {
  const query = "/analytics-catalog/source/snapshot/query";
  const data = { kind: "list", filter: { op: "match-all" } };
  expect((await request.post(query, { data })).status()).toBe(401);
  const headers = { [DevelopmentCallerHeaders.USER]: "eve", [DevelopmentCallerHeaders.ROLES]: "analytics.catalog.admin" };
  expect((await request.post(query, { data, headers })).status()).toBe(401);
  // The gateway strips a client's own CoSec-Identity: the service sees an anonymous request, not a malformed one.
  const forged = { "CoSec-Identity": "forged" };
  expect((await request.post(query, { data, headers: forged })).status()).toBe(
    401,
  );
});

test("an analyst signs in with a CoSec token and drafts a source in its name", async ({
  page,
  request,
}) => {
  const table = `cosec_${Date.now().toString(36)}`;
  const source = await putTable(request, table, [
    { name: "tenant", type: "String" },
  ]);
  await open(page, "/#/draft");
  await signIn(page, EDITOR);
  await expect(page.getByRole("link", { name: /Caller: eve/ })).toBeVisible();
  const form = page.getByRole("form", { name: "Draft a source" });
  await form.getByLabel("Connection").fill(CONNECTION);
  await form.getByLabel("Database").fill(DATABASE);
  await form.getByLabel("Table").fill(table);
  await form.getByRole("button", { name: "Draft" }).click();
  await expect(form.getByText(`Drafted ${source}.`)).toBeVisible();
});

test("a token the gateway refuses signs the console out", async ({ page }) => {
  await open(page);
  await signIn(
    page,
    accessToken(
      "mallory",
      ["analytics.catalog.admin"],
      -60,
    ),
  );
  await expect(page.getByText("Sign in to read the catalog")).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Caller: No caller/ }),
  ).toBeVisible();
});
