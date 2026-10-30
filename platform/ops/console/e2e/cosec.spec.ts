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
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

/*
 * The ops console against the ops server in its production shape, the
 * `cosec` profile, behind the CoSec gateway (here its stand-in,
 * `e2e/cosec-gateway.mjs`): the operator signs in with a CoSec access token,
 * which the gateway verifies and forwards as the identity the service reads
 * the operator from. No identity header a client sends is trusted.
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
  exhausted = await publish(request, context, "pay-1", "exhausted");
  unrecoverable = await publish(request, context, "pay-2", "unrecoverable", 1);
  for (const entry of [exhausted, unrecoverable])
    await listed(request, entry, { Authorization: `Bearer ${OPERATOR}` });
});

test("a request without a token or with an expired one is answered 401, and no identity header is trusted", async ({
  request,
}) => {
  const state = `/ops/parked/${encodeURIComponent(exhausted.id)}/state`;
  expect((await request.get(state)).status()).toBe(401);
  const headers = { [DevelopmentCallerHeaders.USER]: "alice", [DevelopmentCallerHeaders.ROLES]: "ops.operator" };
  expect((await request.get(state, { headers })).status()).toBe(401);
  // The gateway strips a client's own CoSec-Identity: the service sees an anonymous request, not a malformed one.
  const forged = { "CoSec-Identity": "forged" };
  expect((await request.get(state, { headers: forged })).status()).toBe(401);
  const expired = accessToken(
    "mallory",
    ["ops.operator"],
    -60,
  );
  const refused = await request.get(state, {
    headers: { Authorization: `Bearer ${expired}` },
  });
  expect(refused.status()).toBe(401);
});

test("an operator signs in with a CoSec token and retries an entry in its name", async ({
  page,
  request,
}) => {
  await open(page);
  await signIn(page, OPERATOR);
  await expect(row(page, exhausted)).toBeVisible();
  await expect(page.getByRole("link", { name: /Caller: alice/ })).toBeVisible();

  await row(page, exhausted).getByRole("button", { name: "Retry" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Reason").fill("The gateway is back.");
  await dialog.getByRole("button", { name: "Retry" }).click();
  await expect(dialog).toHaveCount(0);
  await expect
    .poll(async () =>
      (await ledgerCommands(request, context)).map((it) => [it.type, it.id]),
    )
    .toContainEqual(["wow.ledger.Retry", exhausted.id]);

  // Its history names the operator the gateway forwarded.
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
    .getByRole("button", { name: "Retry" })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Retry" })
    .click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "it needs ops.operator. (wow.auth.forbidden)" }),
  ).toBeVisible();
});

test("a principal with neither ops role reads nothing: the service refuses its reads as forbidden (UI-1)", async ({
  page,
  request,
}) => {
  const query = "/ops/parked/snapshot/query";
  const state = `/ops/parked/${encodeURIComponent(exhausted.id)}/state`;
  const everything = { op: "match-all" };
  for (const token of [
    accessToken("nobody", []),
    accessToken("stranger", ["someone.else"]),
  ]) {
    const headers = { Authorization: `Bearer ${token}` };
    for (const answer of [
      await request.post(query, { headers, data: { kind: "count", filter: everything } }),
      await request.post(query, { headers, data: { kind: "list", filter: everything } }),
      await request.get(state, { headers }),
    ]) {
      expect(answer.status()).toBe(403);
      expect((await answer.json()).code).toBe("wow.auth.forbidden");
    }
  }

  await open(page);
  await signIn(page, accessToken("nobody", []));
  await expect(
    page.getByText(
      "This caller has neither ops.viewer nor ops.operator: the service shows it no entry.",
    ),
  ).toBeVisible();
  // The workbench's query is refused (403): the view says so, and lists nothing.
  await expect(
    page.getByText("You do not have permission to view this data.").first(),
  ).toBeVisible();
  await expect(row(page, exhausted)).toHaveCount(0);
  await expect(row(page, unrecoverable)).toHaveCount(0);
});

test("a token the gateway refuses signs the console out", async ({ page }) => {
  await open(page);
  await signIn(
    page,
    accessToken(
      "mallory",
      ["ops.operator"],
      -60,
    ),
  );
  await expect(
    page.getByText("Sign in to read the parked entries"),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Caller: No caller/ }),
  ).toBeVisible();
});

test("a token the gateway refuses later signs the console out, saying so", async ({
  page,
}) => {
  await open(page);
  // Well formed and unexpired, so the console takes it; signed with another
  // secret, so the gateway answers 401.
  await signIn(
    page,
    accessToken("mallory", ["ops.operator"], 3600, "a-secret-the-gateway-does-not-know"),
  );
  await expect(
    page.getByText(
      "Your CoSec access token was refused (401): sign in with another.",
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Caller: No caller/ }),
  ).toBeVisible();
});
