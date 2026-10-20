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

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { EDITOR, renderConsole } from "@/app/testing.tsx";

const SERVED = [
  {
    id: "shop.order_wide",
    entry: {
      contextName: "shop",
      name: "order_wide",
      title: "Orders",
      version: "v1",
    },
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
    expect(
      await within(form).findByText(/Drafted default:shop:orders/),
    ).toBeVisible();
    expect(within(form).getByRole("link", { name: "Sources" })).toHaveAttribute(
      "href",
      "/sources",
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
    const editor = within(form).getByRole("textbox", { name: /Definition/ });
    await userEvent.type(editor, "x");
    expect(within(form).getByRole("alert")).toHaveTextContent(
      "The definition is not JSON",
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
  });
});

describe("the rows page", () => {
  afterEach(() => localStorage.clear());

  it("reads the rows of the first dataset served", async () => {
    const { refresh } = renderConsole(EDITOR, {
      path: "/rows",
      served: SERVED,
    });
    expect(await screen.findByText("Hangzhou")).toBeVisible();
    expect(screen.getByRole("combobox", { name: "Dataset" })).toHaveValue(
      "shop.order_wide",
    );
    await userEvent.click(screen.getByRole("button", { name: "Read again" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2));
  });

  it("says when no dataset is served", async () => {
    renderConsole(EDITOR, { path: "/rows" });
    expect(await screen.findByText(/No dataset is served yet/)).toBeVisible();
  });
});
