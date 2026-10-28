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

import { WowProblemError } from "@ahoo-wang/wow-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import {
  DATASET_ROWS,
  EDITOR,
  renderConsole,
  SOURCE_ROWS,
} from "@/app/testing.tsx";

const open = (page: string, id: string) =>
  `/${page}?id=${encodeURIComponent(id)}`;

describe("a source's detail", () => {
  afterEach(() => localStorage.clear());

  it("revises an analyst's table's scope and references", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: open("sources", SOURCE_ROWS[0].aggregateId),
    });
    const form = await screen.findByRole("form", { name: "Scope" });
    const owner = within(form).getByRole("combobox", { name: "Owner column" });
    await userEvent.type(owner, "buyer");
    const references = within(form).getByRole("textbox", {
      name: "References",
    });
    expect(references).toHaveValue("customer_id=crm.customer");
    await userEvent.type(references, "\nbroken");
    expect(within(form).getByRole("alert")).toHaveTextContent("Line 2");
    expect(within(form).getByRole("button", { name: "Save" })).toBeDisabled();
    await userEvent.clear(references);
    await userEvent.type(references, "customer_id=crm.customer");
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(commands.reviseSource).toHaveBeenCalledWith(
        SOURCE_ROWS[0].aggregateId,
        {
          tenantColumn: "tenant_id",
          ownerColumn: "buyer",
          spaceColumn: null,
          platform: false,
          references: [
            { path: "customer_id", context: "crm", aggregate: "customer" },
          ],
        },
      ),
    );
    expect(await within(form).findByRole("status")).toHaveTextContent("Saved.");
  });

  it("declares a platform dimension without scope columns", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: open("sources", SOURCE_ROWS[0].aggregateId),
    });
    const form = await screen.findByRole("form", { name: "Scope" });
    await userEvent.click(
      within(form).getByRole("checkbox", { name: "Platform dimension" }),
    );
    expect(
      within(form).getByRole("combobox", { name: "Tenant column" }),
    ).toBeDisabled();
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(commands.reviseSource).toHaveBeenCalledWith(
        SOURCE_ROWS[0].aggregateId,
        expect.objectContaining({ platform: true, tenantColumn: null }),
      ),
    );
  });

  it("reviews the columns' protection: what changed, and every column not reviewed yet", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: open("sources", SOURCE_ROWS[0].aggregateId),
    });
    const form = await screen.findByRole("form", { name: "Sensitivity" });
    expect(within(form).getAllByText("Not reviewed")).toHaveLength(3);
    expect(
      within(form).getByRole("spinbutton", { name: "Keep first id" }),
    ).toBeDisabled();
    await userEvent.selectOptions(
      within(form).getByRole("combobox", { name: "Level id" }),
      "CONFIDENTIAL",
    );
    const suffix = within(form).getByRole("spinbutton", {
      name: "Keep last buyer",
    });
    await userEvent.clear(suffix);
    await userEvent.type(suffix, "4");
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(commands.reviewSensitivity).toHaveBeenCalledWith(
        SOURCE_ROWS[0].aggregateId,
        [
          { path: "id", level: "confidential" },
          { path: "tenant_id", level: null },
          { path: "buyer", level: "display", keepPrefix: 1, keepSuffix: 4 },
          { path: "customer_id", level: null },
        ],
      ),
    );
    expect(await within(form).findByRole("status")).toHaveTextContent("Saved.");
  });

  it("says the catalog's refusal of an edit", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: open("sources", SOURCE_ROWS[0].aggregateId),
    });
    commands.reviseSource.mockRejectedValue(
      new WowProblemError({
        type: "about:blank",
        title: "Bad Request",
        status: 400,
        detail: "nothing is not a column.",
      }),
    );
    const form = await screen.findByRole("form", { name: "Scope" });
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent(
      "nothing is not a column.",
    );
  });

  it("reads a Wow table as its catalog row declares it, and its pending protection", async () => {
    renderConsole(EDITOR, {
      path: open("sources", SOURCE_ROWS[1].aggregateId),
    });
    expect(
      await screen.findByText(
        /A Wow table: its wow_catalog row declares its scope/,
      ),
    ).toBeVisible();
    expect(screen.queryByRole("form", { name: "Scope" })).toBeNull();
    expect(screen.getByText("OrderCreated/phone")).toBeVisible();
    expect(screen.getByText("shop.order")).toBeVisible();
    const form = screen.getByRole("form", { name: "Sensitivity" });
    expect(within(form).getByText("OrderCreated/address")).toBeVisible();
  });
});

describe("a dataset's detail", () => {
  afterEach(() => localStorage.clear());

  it("says why it needs revision, and revises its definition", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: open("datasets", DATASET_ROWS[0].aggregateId),
    });
    expect(
      await screen.findByText("default:shop:orders lost its column id."),
    ).toBeVisible();
    const form = screen.getByRole("form", { name: "Definition" });
    const editor = within(form).getByRole("textbox", { name: "Definition" });
    await userEvent.clear(editor);
    await userEvent.type(editor, "{{");
    expect(within(form).getByRole("alert")).toHaveTextContent(
      "The definition is not JSON",
    );
    await userEvent.clear(editor);
    await userEvent.type(
      editor,
      '{{"subject":"o","sources":[[],"columns":[[]}',
    );
    const title = within(form).getByRole("textbox", { name: "Title" });
    await userEvent.clear(title);
    await userEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(commands.reviseDataset).toHaveBeenCalledWith(
        DATASET_ROWS[0].aggregateId,
        { subject: "o", sources: [], columns: [] },
        undefined,
      ),
    );
  });

  it("lists the versions published, the serving one open with its DDL", async () => {
    renderConsole(EDITOR, {
      path: open("datasets", DATASET_ROWS[0].aggregateId),
    });
    const versions = await screen.findByRole("region", { name: "Versions" });
    expect(within(versions).getByText("Serving")).toBeVisible();
    expect(within(versions).getByText("→ shop.order")).toBeVisible();
    expect(
      within(versions).getByText(
        /CREATE VIEW wow_datasets.ds_shop_order_wide_v1/,
      ),
    ).toBeVisible();
  });

  it("says what awaits an approver", async () => {
    renderConsole(EDITOR, {
      path: open("datasets", DATASET_ROWS[1].aggregateId),
    });
    expect(await screen.findByText("Awaiting approval")).toBeVisible();
    expect(screen.queryByRole("region", { name: "Versions" })).toBeNull();
  });
});
