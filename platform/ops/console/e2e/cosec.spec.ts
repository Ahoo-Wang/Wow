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
import { ledgerCommands, listed, publish, type Parked } from "./ledger.ts";

/*
 * The ops console against the ops server in its production shape, the
 * `cosec` profile (roadmap P.15): the operator signs in with a CoSec access
 * token, which the service verifies and reads the operator from. No identity
 * header is trusted there.
 */

const OPERATOR = accessToken("alice", ["ops.operator"]);
const VIEWER = accessToken("victor", ["ops.viewer"]);

/** The console in English, signed in as nobody. */
async function open(page: Page) {
  await page.addInitScript(() =>
    localStorage.setItem("wow-ops-console.locale", "en"),
  );
  await page.goto("/#/parked");
}

async function signIn(page: Page, token: string) {
  await expect(
    page.getByText("Sign in to read the parked entries"),
  ).toBeVisible();
  await page.getByLabel("Access token").fill(token);
  await page.getByRole("button", { name: "Sign in" }).click();
}

function row(page: Page, entry: Parked) {
  return page.getByRole("row").filter({ hasText: entry.aggregateId });
}

let context: string;
let exhausted: Parked;
let unrecoverable: Parked;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  context = `e2e-cosec-${Date.now().toString(36)}`;
  exhausted = await publish(request, context, "pay-1", "EXHAUSTED");
  unrecoverable = await publish(request, context, "pay-2", "UNRECOVERABLE", 1);
  for (const entry of [exhausted, unrecoverable])
    await listed(request, entry, { Authorization: `Bearer ${OPERATOR}` });
});

test("the service answers a request without a token 401, and trusts no identity header", async ({
  request,
}) => {
  const state = `/ops/parked/${encodeURIComponent(exhausted.id)}/state`;
  expect((await request.get(state)).status()).toBe(401);
  const headers = { "X-User": "alice", "X-Role": "ops.operator" };
  expect((await request.get(state, { headers })).status()).toBe(401);
  const forged = accessToken(
    "mallory",
    ["ops.operator"],
    "not-the-service-secret-of-32-characters",
  );
  const refused = await request.get(state, {
    headers: { Authorization: `Bearer ${forged}` },
  });
  expect(refused.status()).toBe(401);
});

test("an operator signs in with a CoSec token and resumes an entry in its name", async ({
  page,
  request,
}) => {
  await open(page);
  await signIn(page, OPERATOR);
  await expect(row(page, exhausted)).toBeVisible();
  await expect(page.getByRole("link", { name: /Caller: alice/ })).toBeVisible();

  await row(page, exhausted).getByRole("button", { name: "Resume" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Reason").fill("The gateway is back.");
  await dialog.getByRole("button", { name: "Resume" }).click();
  await expect(dialog).toHaveCount(0);
  await expect
    .poll(async () =>
      (await ledgerCommands(request, context)).map((it) => [it.type, it.id]),
    )
    .toContainEqual(["wow.ledger.Resume", exhausted.id]);

  // Its history names the operator the token signed in.
  await row(page, exhausted)
    .getByText(exhausted.aggregateId, { exact: true })
    .click();
  await expect(page.getByTestId("parked-history")).toContainText("by alice");
});

test("a viewer reads the entries, and the service refuses its actions", async ({
  page,
}) => {
  await open(page);
  await signIn(page, VIEWER);
  await expect(
    page.getByText("This caller has no ops.operator role"),
  ).toBeVisible();
  await row(page, unrecoverable)
    .getByRole("button", { name: "Resume" })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Resume" })
    .click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "it needs ops.operator. (wow.auth.forbidden)" }),
  ).toBeVisible();
});

test("a token the service refuses signs the console out", async ({ page }) => {
  await open(page);
  await signIn(
    page,
    accessToken(
      "mallory",
      ["ops.operator"],
      "not-the-service-secret-of-32-characters",
    ),
  );
  await expect(
    page.getByText("Sign in to read the parked entries"),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Caller: No caller/ }),
  ).toBeVisible();
});
