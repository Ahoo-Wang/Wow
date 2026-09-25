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

import { expect, test, type APIRequestContext } from "@playwright/test";

// The smoke of the console against a real compensation server: nothing is
// stubbed. It runs only when WOW_COMPENSATION_URL names a server that serves
// this build (see playwright.config.ts and RELEASING.md §C′ step 5), and it
// writes: it seeds its own failed executions, under processor names no other
// run shares, so it can say exactly which rows it expects.

const RUN = Date.now().toString(36);
const PROCESSORS = [`SmokeSaga${RUN}A`, `SmokeSaga${RUN}B`];
/** Its own execution, so preparing it leaves the others' queues as they were. */
const PREPARED = `SmokeSaga${RUN}C`;

/** The executions it seeded, by processor: the server names them. */
const SEEDED = new Map<string, string>();

async function seedFailedExecution(
  request: APIRequestContext,
  processorName: string,
): Promise<string> {
  const response = await request.post(
    "/execution_failed/create_execution_failed",
    {
      headers: { "Command-Wait-Stage": "SNAPSHOT" },
      data: {
        eventId: {
          id: `${processorName}-event`,
          version: 1,
          aggregateId: {
            contextName: "views-smoke",
            aggregateName: "order",
            aggregateId: `${processorName}-order`,
            tenantId: "(0)",
          },
        },
        function: {
          contextName: "views-smoke",
          processorName,
          name: "onOrderCreated",
          functionKind: "EVENT",
        },
        error: {
          errorCode: "VIEWS_SMOKE",
          errorMsg: `views smoke ${processorName}`,
          stackTrace: "",
          bindingErrors: [],
        },
        executeAt: Date.now(),
        recoverable: "RECOVERABLE",
      },
    },
  );
  expect(response.ok(), await response.text()).toBe(true);
  const result = await response.json();
  expect(result).toMatchObject({ succeeded: true });
  return result.aggregateId;
}

test.beforeAll(async ({ request }) => {
  for (const processor of [...PROCESSORS, PREPARED])
    SEEDED.set(processor, await seedFailedExecution(request, processor));
});

test("the failed executions show real rows and a filter narrows them", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // Straight to the route: the server answers it with the console.
  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(workbench).toBeVisible();

  // Real rows: the newest first, so both seeded executions are on page one.
  for (const processor of PROCESSORS)
    await expect(
      workbench.getByRole("row").filter({ hasText: processor }),
    ).toHaveCount(1);

  // A condition on the processor, picked in the filter editor and applied.
  const toggle = workbench.getByRole("button", { name: /^Filter/ });
  if ((await toggle.getAttribute("aria-expanded")) !== "true")
    await toggle.click();
  await workbench.getByRole("button", { name: "Add", exact: true }).click();
  const picker = page.getByRole("dialog");
  await picker
    .getByRole("checkbox", { name: "Processor", exact: true })
    .check();
  await picker.getByRole("button", { name: "Done" }).click();
  await expect(picker).toBeHidden();
  const value = workbench.getByRole("combobox", { name: "Processor value" });
  await value.fill(PROCESSORS[0]);
  await value.press("Enter");
  await workbench.getByRole("button", { name: "Apply", exact: true }).click();

  // A record is a row with its own box; "Select all rows" is not one.
  const rows = workbench.getByRole("checkbox", {
    name: /^Select (?!all rows$)/,
  });
  await expect(rows).toHaveCount(1);
  await expect(
    workbench.getByRole("row").filter({ hasText: PROCESSORS[0] }),
  ).toHaveCount(1);
  await expect(
    workbench.getByRole("row").filter({ hasText: PROCESSORS[1] }),
  ).toHaveCount(0);

  expect(failures).toEqual([]);
});

test("the time queues ask the server's clock and it answers", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // A new failure is retryable at once: in "To retry", whose condition
  // carries BEFORE_NOW for the timed-out preparations.
  await page.goto("/executions?view=system:execution-failed:to-retry");
  const toRetry = page.getByRole("region", { name: "To retry" });
  await expect(toRetry).toBeVisible();
  for (const processor of PROCESSORS)
    await expect(
      toRetry.getByRole("row").filter({ hasText: processor }),
    ).toHaveCount(1);

  // Its first retry is scheduled after the minimum backoff, so it is not
  // due yet: "Due for retry" asks `NOR AFTER_NOW` of the next retry. The
  // answer itself is read, since no row is what an unanswered page shows too.
  const answered = page.waitForResponse(
    (response) =>
      response.url().endsWith("/execution_failed/snapshot/paged") &&
      response.request().method() === "POST",
  );
  await page.goto("/executions?view=system:execution-failed:next-retry");
  const response = await answered;
  expect(response.ok(), await response.text()).toBe(true);
  expect(JSON.stringify(response.request().postDataJSON())).toContain(
    '"op":"AFTER_NOW"',
  );
  const { list } = (await response.json()) as {
    list: Array<{ state: { function: { processorName: string } } }>;
  };
  expect(
    list.filter(({ state }) =>
      PROCESSORS.includes(state.function.processorName),
    ),
  ).toEqual([]);
  await expect(
    page.getByRole("region", { name: "Due for retry" }),
  ).toBeVisible();

  expect(failures).toEqual([]);
});

test("a row's prepare reaches the server, and the row reads it back", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  const row = workbench.getByRole("row").filter({ hasText: PREPARED });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Failed");

  // The generated client's command, waiting for the snapshot, so the
  // refresh that follows reads the prepared state.
  const sent = page.waitForResponse(
    (response) =>
      response.url().endsWith("/prepare_compensation") &&
      response.request().method() === "PUT",
  );
  await row.getByRole("button", { name: "Prepare", exact: true }).click();
  const response = await sent;
  expect(response.ok(), await response.text()).toBe(true);
  expect(response.request().headers()["command-wait-stage"]).toBe("SNAPSHOT");

  await expect(workbench.locator('[data-slot="bulk-status"]')).toContainText(
    "Prepare · 1 done",
  );
  await expect(row).toContainText("Prepared");
  // In progress now, so it cannot be prepared again until it times out.
  await expect(
    row.getByRole("button", { name: "Prepare", exact: true }),
  ).toBeDisabled();

  expect(failures).toEqual([]);
});

test("a link opens an execution's detail, with its history from the event stream", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const id = SEEDED.get(PROCESSORS[0])!;

  const history = page.waitForResponse((response) =>
    response.url().endsWith("/execution_failed/event/paged"),
  );
  await page.goto(`/executions?id=${encodeURIComponent(id)}`);
  const panel = page.getByRole("dialog", { name: id });
  await expect(panel.getByRole("heading", { name: id })).toBeVisible();
  await expect(
    panel.getByRole("region", { name: "Function", exact: true }),
  ).toContainText(PROCESSORS[0]);
  await expect(
    panel.getByRole("form", { name: "Apply retry specification" }),
  ).toBeVisible();
  // Seeded without one.
  await expect(
    panel.getByRole("region", { name: "Stack trace", exact: true }),
  ).toContainText("No stack trace");
  // The server's event stream answers for this execution, and its first
  // stream is the failure that created it.
  expect((await history).ok()).toBe(true);
  await expect(
    panel.locator('[data-section="history"]').getByRole("row").nth(1),
  ).toContainText("First failed");

  expect(failures).toEqual([]);
});

test("an old queue address opens its view, narrowed to the window it names", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // The server answers the old address with the console, which sends it on
  // to its view with the window as the view's scope: the last hour holds
  // what this run seeded.
  const end = Date.now() + 60_000;
  const start = end - 3_600_000;
  await page.goto(`/to-retry?start=${start}&end=${end}`);
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Ato-retry&start=\d+&end=\d+$/,
  );
  const toRetry = page.getByRole("region", { name: "To retry" });
  await expect(toRetry).toBeVisible();
  for (const processor of PROCESSORS)
    await expect(
      toRetry.getByRole("row").filter({ hasText: processor }),
    ).toHaveCount(1);
  await expect(
    page.getByText("This view is narrowed by the link it was opened from."),
  ).toBeVisible();

  // Refreshed, the server serves the view's own address too.
  await page.reload();
  await expect(page.getByRole("region", { name: "To retry" })).toBeVisible();

  expect(failures).toEqual([]);
});

test("a stream of an execution's history opens with its event payload", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const id = SEEDED.get(PROCESSORS[1])!;

  await page.goto(`/executions?id=${encodeURIComponent(id)}`);
  const panel = page.getByRole("dialog", { name: id });
  const first = panel
    .locator('[data-section="history"]')
    .getByRole("row")
    .filter({ hasText: "First failed" });
  await expect(first).toHaveCount(1);
  // Opened, the stream reads whole in a drawer over the execution's: the
  // payload the server stored, the failure this run seeded.
  await first.press("Enter");
  const stream = page
    .locator('[data-slot="record-detail"]')
    .filter({ hasNotText: "Apply retry specification" });
  await expect(stream).toContainText(`views smoke ${PROCESSORS[1]}`);
  // Escape closes it alone.
  await page.keyboard.press("Escape");
  await expect(stream).toHaveCount(0);
  await expect(panel).toBeVisible();

  expect(failures).toEqual([]);
});

test("the overview board counts what the server holds", async ({
  page,
  request,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // What the server holds, asked directly: every active failure.
  const answer = await request.post("/execution_failed/snapshot/aggregation", {
    data: {
      filter: {
        op: "IN",
        field: "state.status",
        values: ["FAILED", "PREPARED"],
      },
      metrics: [{ type: "COUNT", alias: "active" }],
    },
  });
  expect(answer.ok(), await answer.text()).toBe(true);
  const [{ active }] = (await answer.json()) as [{ active: number }];

  await page.goto("/");
  const figure = (title: string) =>
    page
      .getByRole("group", { name: title, exact: true })
      .locator("[data-slot='metric-value']");
  await expect(figure("All active")).toHaveText(active.toLocaleString("en-US"));
  // Written just now, the seeded failures are in the default window: the
  // counts over the snapshots and over the event streams — each stream's
  // events counted by name, the net backlog worked out by the server — all
  // answer.
  const number = async (title: string) =>
    Number((await figure(title).innerText()).replace(/,/g, ""));
  expect(await number("Active in range")).toBeGreaterThanOrEqual(SEEDED.size);
  expect(await number("New failures")).toBeGreaterThanOrEqual(SEEDED.size);
  await expect(figure("Net backlog")).toHaveText(/^-?[\d,]+$/);
  await expect(figure("Retry success")).toBeVisible();
  // 「可立即处理」 is the due-for-retry panel's own total.
  const actionable = await figure("Actionable now").innerText();
  await expect(
    page.getByRole("group", {
      name: "Needing attention — due for retry",
      exact: true,
    }),
  ).toBeVisible();
  if (actionable !== "0")
    await expect(page.getByText(`${actionable} records in all`)).toBeVisible();
  expect(failures).toEqual([]);
});
