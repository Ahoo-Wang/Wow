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

import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import {
  defaultRecordConfig,
  emptyDashboardConfig,
  type DataViewDefinition,
} from "@ahoo-wang/wow-view-engine";
import {
  catalogEngine,
  EDITOR,
  renderConsole,
  SETTINGS,
} from "@/app/testing.tsx";
import { BOARDS } from "@/views/boards.ts";

const SERVED = [
  {
    id: "shop.order_wide",
    entry: {
      context: "shop",
      dataset: "order_wide",
      title: "Orders",
      version: "v1",
    },
    catalog: 0,
  },
];

describe("the draft page", () => {
  afterEach(() => localStorage.clear());

  it("drafts an analyst's table under the catalog's connection", async () => {
    const { commands } = renderConsole(EDITOR, { path: "/draft" });
    const form = await screen.findByRole("form", { name: "Draft a source" });
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Database" }),
      "shop",
    );
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Table" }),
      "orders",
    );
    await userEvent.click(within(form).getByRole("button", { name: "Draft" }));
    await waitFor(() =>
      expect(commands.draftSource).toHaveBeenCalledWith(
        "shop",
        "orders",
        "default:shop:orders",
      ),
    );
    // Said once, with the way to the source drafted: open on its page.
    expect(
      await within(form).findByText(/Drafted default:shop:orders/),
    ).toBeVisible();
    expect(within(form).queryByText("Saved.")).toBeNull();
    expect(within(form).getByRole("link", { name: "Open it" })).toHaveAttribute(
      "href",
      "/sources?id=default%3Ashop%3Aorders",
    );
  });

  it("starts from the catalog's own connection, named by its label alone", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: "/draft",
      settings: { ...SETTINGS, connection: "warehouse" },
    });
    const form = await screen.findByRole("form", { name: "Draft a source" });
    const connection = within(form).getByRole("textbox", {
      name: "Connection",
    });
    expect(connection).toHaveValue("warehouse");
    expect(connection).toHaveAccessibleDescription(
      /The catalog's warehouse connection/,
    );
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Database" }),
      "analytics",
    );
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Table" }),
      "shipments",
    );
    await userEvent.click(within(form).getByRole("button", { name: "Draft" }));
    await waitFor(() =>
      expect(commands.draftSource).toHaveBeenCalledWith(
        "analytics",
        "shipments",
        "warehouse:analytics:shipments",
      ),
    );
  });

  it("drafts a dataset from its definition, refusing one that is not JSON", async () => {
    const { commands } = renderConsole(EDITOR, { path: "/draft" });
    const form = await screen.findByRole("form", { name: "Draft a dataset" });
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Context" }),
      "shop",
    );
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Name" }),
      "order_wide",
    );
    await userEvent.type(
      within(form).getByRole("textbox", { name: "Title" }),
      "Orders",
    );
    // Named by its label, not by the JSON it holds.
    const editor = within(form).getByRole("textbox", { name: "Definition" });
    expect(editor).toHaveAccessibleDescription(/^JSON: subject, sources/);
    await userEvent.type(editor, "x");
    const lines = String((editor as HTMLTextAreaElement).value).split("\n");
    // Where, in the console's words, whatever the browser's parser says.
    expect(within(form).getByRole("alert")).toHaveTextContent(
      `The definition is not JSON: look at line ${lines.length}, column 2.`,
    );
    await userEvent.type(editor, "{Backspace}");
    await userEvent.click(within(form).getByRole("button", { name: "Draft" }));
    await waitFor(() =>
      expect(commands.draftDataset).toHaveBeenCalledWith(
        "shop",
        "order_wide",
        expect.objectContaining({ subject: "o" }),
        "Orders",
      ),
    );
    expect(
      await within(form).findByText(/Drafted shop.order_wide/),
    ).toBeVisible();
    expect(within(form).getByRole("link", { name: "Open it" })).toHaveAttribute(
      "href",
      "/datasets?id=shop.order_wide",
    );
  });
});

describe("the rows page", () => {
  afterEach(() => localStorage.clear());

  it("reads the rows of the first dataset served, read again for another caller", async () => {
    const { refresh, callers } = renderConsole(EDITOR, {
      path: "/rows",
      served: SERVED,
    });
    expect(await screen.findByText("Hangzhou")).toBeVisible();
    expect(screen.getByLabelText("Dataset")).toHaveTextContent(
      "Orders (shop.order_wide)",
    );
    act(() => callers.set({ ...EDITOR, user: "bob" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2));
  });

  it("acts on the aggregate a row refers to, through the catalog", async () => {
    const { send } = renderConsole(EDITOR, {
      path: "/rows?id=o-1",
      served: SERVED,
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Actions for o-1" }),
    );
    await userEvent.click(
      await screen.findByRole("menuitem", {
        name: "SuspendCustomer · customer",
      }),
    );
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/reason/), "spam");
    await userEvent.click(
      within(dialog).getByRole("button", {
        name: "SuspendCustomer · customer",
      }),
    );
    await waitFor(() =>
      expect(send).toHaveBeenCalledWith(
        "shop.order_wide",
        "customer",
        "c-1",
        "SuspendCustomer",
        { reason: "spam" },
      ),
    );
  });

  it("says when no dataset is served", async () => {
    renderConsole(EDITOR, { path: "/rows" });
    expect(await screen.findByText("No dataset yet")).toBeVisible();
  });
});

describe("the boards page", () => {
  afterEach(() => localStorage.clear());

  it("shows a board whose panels name views of the datasets served", async () => {
    const engine = catalogEngine(SERVED);
    const view = await engine.save(
      engine.create("shop.order_wide", {
        title: "All orders, saved",
        scope: "personal",
        config: defaultRecordConfig(
          engine.definitions.get("shop.order_wide") as DataViewDefinition,
        ),
      }),
    );
    const board = await engine.save(
      engine.create(BOARDS, {
        title: "Orders board",
        scope: "personal",
        config: {
          ...emptyDashboardConfig(),
          panels: [
            {
              id: "orders",
              kind: "view",
              instanceId: view.id,
              bindings: [],
              layout: { x: 0, y: 0, w: 12, h: 4 },
            },
          ],
        },
      }),
    );
    renderConsole(EDITOR, {
      path: `/boards?view=${encodeURIComponent(board.id)}`,
      served: SERVED,
      engine,
    });
    expect((await screen.findAllByText("Orders board")).length).toBeGreaterThan(
      0,
    );
    expect(
      (await screen.findAllByText("All orders, saved")).length,
    ).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "Dashboards" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
});
