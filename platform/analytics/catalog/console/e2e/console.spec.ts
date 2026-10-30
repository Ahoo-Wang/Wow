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

import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { CONNECTION, DATABASE, putTable, views } from "./warehouse.ts";
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

/*
 * The analytics catalog console end to end (roadmap 7.2c): the console as
 * the catalog server serves it, against the server's own routes on its
 * memory backend, its warehouse the server's warehouse simulator. Everything
 * the console does goes through the catalog as a data owner's would.
 */

interface Caller {
  user: string;
  roles: string[];
}

const EDITOR: Caller = { user: "eve", roles: ["analytics.catalog.editor"] };
const APPROVER: Caller = { user: "abe", roles: ["analytics.catalog.approver"] };
const VIEWER: Caller = { user: "vic", roles: ["analytics.catalog.viewer"] };

/** The console in English, as `caller` (none: the console asks first). */
async function open(page: Page, caller: Caller | null, path = "/#/sources") {
  await page.addInitScript(
    ([who]) => {
      localStorage.setItem("wow-analytics-catalog-console.locale", "en");
      if (who)
        localStorage.setItem(
          "wow-analytics-catalog-console.caller",
          JSON.stringify({ ...who, tenant: "(platform)" }),
        );
    },
    [caller] as const,
  );
  await page.goto(path);
}

/** Confirms the action `name` the panel offers, in its dialog. */
async function act(page: Page, name: string) {
  await page.getByRole("button", { name, exact: true }).first().click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name, exact: true }).click();
}

/** Confirms the action `name` the record's 「⋯」 menu offers, in its dialog. */
async function actFromMenu(page: Page, record: string, name: string) {
  await page.getByRole("button", { name: `Actions for ${record}` }).click();
  await page.getByRole("menuitem", { name, exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name, exact: true })
    .click();
}

/** The state of the dataset `id`, as the catalog has it. */
async function datasetState(
  request: APIRequestContext,
  id: string,
): Promise<{ status: string; current: number; requestReasons: string[] }> {
  const state = await request.get(
    `/analytics-catalog/dataset/${encodeURIComponent(id)}/state`,
    {
      headers: {
        [DevelopmentCallerHeaders.USER]: EDITOR.user,
        [DevelopmentCallerHeaders.ROLES]: EDITOR.roles[0],
      },
    },
  );
  return ((await state.json()) as { state: never }).state;
}

let table: string;
let source: string;
let dataset: string;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  const suffix = Date.now().toString(36);
  table = `orders_${suffix}`;
  dataset = `shop.orders_${suffix}`;
  source = await putTable(request, table, [
    { name: "tenant", type: "String" },
    { name: "buyer", type: "String" },
    { name: "total", type: "Int64" },
  ]);
});

test("the catalog server serves the console, which asks for a caller first", async ({
  page,
}) => {
  await open(page, null);
  await expect(page.getByText("Sign in to read the catalog")).toBeVisible();
  await page.getByLabel("User").fill(EDITOR.user);
  await page.getByLabel("Roles").fill(EDITOR.roles.join(","));
  await page.getByRole("button", { name: "Use this caller" }).click();
  await expect(
    page.getByRole("button", { name: "All system", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /Caller: eve/ })).toBeVisible();
  // The page says its language and its name.
  await expect(page).toHaveTitle("Wow Analytics Catalog");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  // Without an approver for a source (the default), none awaits one.
  await expect(
    page.getByRole("button", { name: "To review system", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Awaiting approval system" }),
  ).toHaveCount(0);
});

test("an analyst drafts a source, declares its scope and reviews its columns", async ({
  page,
}) => {
  await open(page, EDITOR, "/#/draft");
  const form = page.getByRole("form", { name: "Draft a source" });
  // The catalog's own connection, read from its settings: the first draft
  // is not refused for another one.
  await expect(form.getByLabel("Connection")).toHaveValue(CONNECTION);
  await form.getByLabel("Database").fill(DATABASE);
  await form.getByLabel("Table").fill(table);
  await form.getByRole("button", { name: "Draft" }).click();
  await expect(form.getByText(`Drafted ${source}.`)).toBeVisible();
  // Said once, with the way to it.
  await expect(form.getByText("Saved.")).toHaveCount(0);
  await form.getByRole("link", { name: "Open it" }).click();
  await expect(page).toHaveURL(
    new RegExp(`#/sources\\?id=${encodeURIComponent(source)}`),
  );

  const scope = page.getByRole("form", { name: "Scope" });
  await scope.getByRole("combobox", { name: "Tenant column" }).fill("tenant");
  await scope.getByRole("button", { name: "Save" }).click();
  await expect(scope.getByText("Saved.")).toBeVisible();

  const sensitivity = page.getByRole("form", { name: "Sensitivity" });
  await sensitivity.getByLabel("Level buyer").selectOption("display");
  await sensitivity.getByLabel("Keep first buyer").fill("1");
  await sensitivity.getByRole("button", { name: "Save" }).click();
  await expect(sensitivity.getByText("Saved.")).toBeVisible();
});

test("an approver marks the source reviewed", async ({ page, request }) => {
  await open(page, APPROVER, `/#/sources?id=${encodeURIComponent(source)}`);
  await act(page, "Mark reviewed");
  await expect
    .poll(async () => {
      const state = await request.get(
        `/analytics-catalog/source/${encodeURIComponent(source)}/state`,
        { headers: { [DevelopmentCallerHeaders.USER]: APPROVER.user, [DevelopmentCallerHeaders.ROLES]: APPROVER.roles[0] } },
      );
      return ((await state.json()) as { state: { status: string } }).state
        .status;
    })
    .toBe("REVIEWED");
});

test("an analyst drafts a dataset over it and publishes it", async ({
  page,
  request,
}) => {
  await open(page, EDITOR, "/#/draft");
  const form = page.getByRole("form", { name: "Draft a dataset" });
  const [context, name] = dataset.split(".");
  await form.getByLabel("Context", { exact: true }).fill(context);
  await form.getByLabel("Name", { exact: true }).fill(name);
  await form.getByRole("textbox", { name: "Definition", exact: true }).fill(
    JSON.stringify({
      subject: "o",
      sources: [{ alias: "o", sourceId: source }],
      columns: [
        { name: "buyer", from: "o.buyer" },
        { name: "total", from: "o.total" },
      ],
    }),
  );
  await form.getByRole("button", { name: "Draft" }).click();
  await expect(form.getByText(`Drafted ${dataset}.`)).toBeVisible();
  await form.getByRole("link", { name: "Open it" }).click();

  await act(page, "Publish");
  await expect
    .poll(() => views(request))
    .toContain(`wow_datasets.ds_${context}_${name}_v1`);
  await expect(page.getByText("PUBLISHED").first()).toBeVisible();
  // Nothing awaited an approver here; the words cannot know ahead.
  await expect(
    page
      .getByText(/published, or awaiting an approver if it needs one/)
      .first(),
  ).toBeVisible();
});

test("a publication computing from a sensitive value awaits an approver, and the approver finds it", async ({
  page,
  request,
}) => {
  const [context, name] = dataset.split(".");
  await open(page, EDITOR, `/#/datasets?id=${encodeURIComponent(dataset)}`);
  const definition = page.getByRole("form", { name: "Definition" });
  await definition.getByRole("textbox", { name: "Definition" }).fill(
    JSON.stringify({
      subject: "o",
      sources: [{ alias: "o", sourceId: source }],
      columns: [
        { name: "buyer", from: "o.buyer" },
        { name: "total", from: "o.total" },
        {
          name: "buyer_hint",
          expression: "concat(substring(o.buyer, 1, 1), '***')",
        },
      ],
    }),
  );
  await definition.getByRole("button", { name: "Save" }).click();
  await expect(definition.getByText("Saved.")).toBeVisible();
  await act(page, "Publish");
  await expect
    .poll(async () => (await datasetState(request, dataset)).requestReasons)
    .not.toEqual([]);
  // An editor may not decide it: offered disabled, with why.
  await page.getByRole("button", { name: `Actions for ${dataset}` }).click();
  await expect(
    page.getByRole("menuitem", { name: "Approve publication", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText("Takes the role analytics.catalog.approver.").first(),
  ).toBeVisible();
  await page.keyboard.press("Escape");

  // The approver, in a page of its own: a hash link to the same page would
  // not load it again as another caller.
  const approver = await page.context().newPage();
  await open(
    approver,
    APPROVER,
    `/#/datasets?view=${encodeURIComponent("system:analytics-catalog-datasets:awaiting-approval")}`,
  );
  const list = approver.getByRole("table", { name: "Awaiting approval" });
  await expect(list.getByText(dataset)).toBeVisible();
  await approver.goto(`/#/datasets?id=${encodeURIComponent(dataset)}`);
  await actFromMenu(approver, dataset, "Approve publication");
  await expect
    .poll(() => views(request))
    .toContain(`wow_datasets.ds_${context}_${name}_v2`);
  await expect
    .poll(async () => (await datasetState(request, dataset)).current)
    .toBe(2);
});

test("an analyst rolls back to a version the detail lists, the serving one first", async ({
  page,
  request,
}) => {
  await open(page, EDITOR, `/#/datasets?id=${encodeURIComponent(dataset)}`);
  const versions = page.getByRole("region", { name: "Versions" });
  await expect(versions.locator("summary").first()).toContainText("v2");
  await expect(versions.locator("summary").first()).toContainText("Serving");
  await versions.getByRole("button", { name: "Roll back to v1" }).click();
  const asked = versions.getByRole("form", { name: "Roll back to v1" });
  await asked.getByRole("button", { name: "Roll back" }).click();
  await expect(versions.getByText("Switched back to v1.")).toBeVisible();
  await expect
    .poll(async () => (await datasetState(request, dataset)).current)
    .toBe(1);
  await expect(versions.locator("summary").first()).toContainText("v1");
  await expect(versions.locator("summary").first()).toContainText("Serving");
});

test("a viewer reads the catalog, and is offered no change it may not make", async ({
  page,
}) => {
  await open(page, VIEWER, `/#/datasets?id=${encodeURIComponent(dataset)}`);
  await expect(page.getByText(/This caller only reads the catalog/)).toBeVisible();
  const publish = page
    .getByRole("button", { name: "Publish", exact: true })
    .first();
  await expect(publish).toHaveAttribute("aria-disabled", "true");
  await expect(publish).toHaveAccessibleDescription(
    "Takes the role analytics.catalog.editor or analytics.catalog.admin.",
  );
});

test("a node without a dataset backend says it serves no datasets", async ({
  page,
}) => {
  // The suite's server runs on its memory backend, which has none.
  await open(page, EDITOR, "/#/rows");
  await expect(page.getByTestId("not-served")).toContainText(
    "This node serves no datasets",
  );
  await expect(page.getByText(/No static resource/)).toHaveCount(0);
});
