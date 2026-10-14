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
import {
  ledgerCommands,
  listed,
  publish,
  resolve,
  type Parked,
} from "./ledger.ts";

/*
 * The ops console end to end (roadmap 7.1b): the console as the ops server
 * serves it, against the server's own routes on its memory backend. An
 * owning service's ledger is the server's ledger simulator; everything the
 * console does goes through the ops service as an operator's would.
 */

interface Caller {
  user: string;
  roles: string[];
}

const OPERATOR: Caller = { user: "alice", roles: ["ops.operator"] };
const VIEWER: Caller = { user: "victor", roles: ["viewer"] };

/** The console in English, as `caller` (none: the console asks first). */
async function open(page: Page, caller: Caller | null, path = "/#/parked") {
  await page.addInitScript(
    ([who]) => {
      localStorage.setItem("wow-ops-console.locale", "en");
      if (who)
        localStorage.setItem(
          "wow-ops-console.caller",
          JSON.stringify({ ...who, tenant: "(0)" }),
        );
    },
    [caller] as const,
  );
  await page.goto(path);
}

/** The workbench's row of `entry`, by its aggregate id. */
function row(page: Page, entry: Parked) {
  return page.getByRole("row").filter({ hasText: entry.aggregateId });
}

/** Opens a system view from the view list. */
async function view(page: Page, title: string) {
  // A system view's entry reads its title and its audience.
  await page
    .getByRole("button", { name: `${title} system`, exact: true })
    .click();
}

let context: string;
let exhausted: Parked;
let scheduled: Parked;
let unrecoverable: Parked;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  context = `e2e-${Date.now().toString(36)}`;
  exhausted = await publish(request, context, "pay-1", "EXHAUSTED");
  scheduled = await publish(request, context, "pay-2", "SCHEDULED", 1);
  unrecoverable = await publish(
    request,
    context,
    "pay-3",
    "UNRECOVERABLE",
    2,
    "wow.subscription.unrecoverable: offset 7 is no longer retained",
  );
  for (const entry of [exhausted, scheduled, unrecoverable])
    await listed(request, entry);
});

test("the ops server serves the console, which asks for a caller first", async ({
  page,
}) => {
  await open(page, null);
  await expect(
    page.getByText("Name a caller to read the parked entries"),
  ).toBeVisible();
  await page.getByLabel("User").fill(OPERATOR.user);
  await page.getByLabel("Roles").fill(OPERATOR.roles.join(","));
  await page.getByRole("button", { name: "Use this caller" }).click();
  await expect(row(page, exhausted)).toBeVisible();
  await expect(page.getByRole("link", { name: /Caller: alice/ })).toBeVisible();
});

test("the queues list the entries by status", async ({ page }) => {
  await open(page, OPERATOR);
  // Needs attention, the default: exhausted and unrecoverable, not the one retrying.
  await expect(row(page, exhausted)).toBeVisible();
  await expect(row(page, unrecoverable)).toBeVisible();
  await expect(row(page, scheduled)).toHaveCount(0);

  await view(page, "Retrying");
  await expect(row(page, scheduled)).toBeVisible();
  await expect(row(page, exhausted)).toHaveCount(0);
  await expect(page).toHaveURL(/view=/);
});

test("the overview counts the entries and clusters them by what failed", async ({
  page,
}) => {
  await open(page, OPERATOR, "/#/overview");
  await expect(
    page.getByRole("heading", { name: "Overview", level: 1 }),
  ).toBeVisible();
  // The pile in numbers: the three parked, two of them needing a person.
  await expect(
    page
      .getByRole("group", { name: "All parked" })
      .getByText("3", { exact: true }),
  ).toBeVisible();
  await expect(
    page
      .getByRole("group", { name: "Needs attention" })
      .getByText("2", { exact: true }),
  ).toBeVisible();
  // The clusters: what failed, where, and how many.
  const clusters = page.getByRole("group", {
    name: "Failure clusters — top 10",
  });
  const refused = clusters
    .getByRole("row")
    .filter({ hasText: "java.net.ConnectException" });
  await expect(refused).toContainText(context);
  await expect(refused).toContainText("2");
  await expect(
    clusters
      .getByRole("row")
      .filter({ hasText: "wow.subscription.unrecoverable" }),
  ).toContainText("1");
  for (const panel of [
    "Parked over time, by status",
    "By status",
    "Top failing subscriptions",
  ])
    await expect(
      page.getByRole("group", { name: panel, exact: true }),
    ).toBeVisible();

  // A cluster opens its parked entries in the workbench.
  await refused.getByText("java.net.ConnectException").click();
  await expect(page).toHaveURL(/#\/parked\?view=system%3Aops-parked%3Aactive/);
  await expect(row(page, exhausted)).toBeVisible();
  await expect(row(page, scheduled)).toBeVisible();
  await expect(row(page, unrecoverable)).toHaveCount(0);
});

test("an entry's detail reads its cause, attempts, retry spec and owning aggregate", async ({
  page,
}) => {
  await open(page, OPERATOR);
  await row(page, exhausted)
    .getByText(exhausted.aggregateId, { exact: true })
    .click();
  const detail = page.getByTestId("parked-reading");
  const failure = detail.getByTestId("parked-failure");
  await expect(failure).toContainText("java.net.ConnectException");
  await expect(failure).toContainText(
    `The gateway is unreachable for ${exhausted.aggregateId}.`,
  );
  // Its history: the owning service parked it.
  const history = detail.getByTestId("parked-history");
  await expect(history.getByRole("listitem")).toHaveCount(1);
  await expect(history).toContainText("Parked");
  await expect(history).toContainText("Exhausted");
  await expect(detail).toContainText(`${context}-service`);
  await expect(detail).toContainText(`${context}.payment`);
  await expect(detail).toContainText("3 of 3");
  await expect(detail).toContainText("PT1M");
  await expect(detail).toContainText("PT2M");
  await expect(page).toHaveURL(/id=/);
});

test("an operator resumes an entry, it goes back to the owning service, and the resolution shows up on its own", async ({
  page,
  request,
}) => {
  await open(page, OPERATOR);
  await row(page, exhausted).getByRole("button", { name: "Resume" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Reason").fill("The gateway is back.");
  await dialog.getByRole("button", { name: "Resume" }).click();
  await expect(dialog).toHaveCount(0);

  // The request is recorded on the entry, and the saga sends it to the owning context.
  await expect(row(page, exhausted)).toContainText("Resume");
  await expect
    .poll(async () =>
      (await ledgerCommands(request, context)).map((it) => [it.type, it.id]),
    )
    .toContainEqual(["wow.ledger.Resume", exhausted.id]);

  // The owning service resumed it: the open view reads the entries again on
  // its own and lets the resolved one go.
  await resolve(request, exhausted, "RESUMED");
  await expect(row(page, exhausted)).toHaveCount(0, { timeout: 30_000 });
  await view(page, "Resolved");
  await expect(row(page, exhausted)).toContainText("Resolved");

  // Its history, newest first: resolved, the operator's resume, parked.
  await row(page, exhausted)
    .getByText(exhausted.aggregateId, { exact: true })
    .click();
  const steps = page.getByTestId("parked-history").getByRole("listitem");
  await expect(steps).toHaveCount(3);
  await expect(steps.nth(0)).toContainText("Resolved");
  await expect(steps.nth(0)).toContainText("Resumed");
  await expect(steps.nth(1)).toContainText("Resume requested");
  await expect(steps.nth(1)).toContainText("The gateway is back.");
  await expect(steps.nth(1)).toContainText("by alice");
  await expect(steps.nth(2)).toContainText("Parked");
});

test("mark recoverable and apply retry spec go back as the ledger's commands", async ({
  page,
  request,
}) => {
  await open(page, OPERATOR);
  const entry = row(page, unrecoverable);
  await entry.getByRole("button", { name: /^Actions for/ }).click();
  await page
    .getByRole("menuitem", { name: "Recoverable", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: /Mark as/ })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await entry.getByRole("button", { name: /^Actions for/ }).click();
  await page.getByRole("menuitem", { name: "Apply retry spec" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Max retries").fill("5");
  await dialog.getByLabel("Min backoff").fill("2m");
  await dialog.getByRole("button", { name: "Apply retry spec" }).click();
  await expect(dialog).toHaveCount(0);

  await expect
    .poll(async () => {
      const sent = await ledgerCommands(request, context);
      return sent
        .filter((it) => it.id === unrecoverable.id)
        .map((it) => it.type);
    })
    .toEqual(
      expect.arrayContaining([
        "wow.ledger.MarkRecoverable",
        "wow.ledger.ApplyRetrySpec",
      ]),
    );
  const sent = await ledgerCommands(request, context);
  const spec = sent.find((it) => it.type === "wow.ledger.ApplyRetrySpec");
  expect(spec?.body).toMatchObject({ maxRetries: 5, minBackoff: "2m" });
});

test("a caller without ops.operator reads the entries, and the service's refusal is said", async ({
  page,
}) => {
  await open(page, VIEWER);
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
  // The problem's own words and code (application/problem+json), said on
  // the outcome line.
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "it needs ops.operator. (wow.auth.forbidden)" }),
  ).toBeVisible();
});
