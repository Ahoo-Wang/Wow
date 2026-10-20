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

import { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { accessToken } from "@/services/testing.ts";
import {
  DATASET_ROWS,
  EDITOR,
  renderConsole,
  SOURCE_ROWS,
} from "./testing.tsx";

describe("the console", () => {
  afterEach(() => localStorage.clear());

  it("asks for a caller first, then lists the sources", async () => {
    const { callers } = renderConsole(null);
    expect(screen.getByText(/Sign in to read the catalog/)).toBeVisible();
    await userEvent.type(screen.getByLabelText("User"), "alice");
    await userEvent.click(
      screen.getByRole("button", { name: "Use this caller" }),
    );
    expect(callers.get()).toEqual(EDITOR);
    const table = await screen.findByRole("table", { name: "All" });
    expect(await within(table).findByText("orders_state")).toBeVisible();
    expect(screen.getByRole("link", { name: "Caller: alice" })).toBeVisible();
  });

  it("signs in with a CoSec access token, and out again", async () => {
    const { callers, router } = renderConsole(null, {
      tokens: new TokenStorage(),
    });
    const token = screen.getByLabelText("Access token");
    await userEvent.type(token, "not-a-token");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(
      await screen.findByText(/This is not a CoSec access token/),
    ).toBeVisible();
    expect(callers.get()).toBeNull();

    await userEvent.clear(token);
    await userEvent.type(
      token,
      `Bearer ${accessToken({ sub: "alice", roles: ["analytics.catalog.editor"] })}`,
    );
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(callers.get()).toEqual({ ...EDITOR, via: "cosec" });

    await router.navigate("/caller");
    expect(
      await screen.findByText("Signed in as alice with a CoSec access token."),
    ).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(callers.get()).toBeNull();
    expect(router.state.location.pathname).toBe("/sources");
  });

  it("says a caller who neither edits nor approves only reads", async () => {
    renderConsole({
      user: "victor",
      tenant: "(platform)",
      roles: ["analytics.catalog.viewer"],
    });
    expect(await screen.findByText(/This caller has neither/)).toBeVisible();
  });

  it("names an approver's role in the bar", async () => {
    renderConsole({ ...EDITOR, roles: ["analytics.catalog.approver"] });
    expect(await screen.findByText("analytics.catalog.approver")).toBeVisible();
  });

  it("speaks Chinese", async () => {
    renderConsole(EDITOR, { locale: "zh-CN" });
    expect(await screen.findByRole("table", { name: "全部" })).toBeVisible();
    expect(screen.getAllByText("数据源").length).toBeGreaterThan(0);
  });

  it("sends an unknown page, and the home page, to the sources", async () => {
    const { router } = renderConsole(EDITOR, { path: "/nowhere" });
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/sources"),
    );
    await router.navigate("/");
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/sources"),
    );
  });

  it("lists the datasets, those that need revision among them", async () => {
    renderConsole(EDITOR, { path: "/datasets" });
    const table = await screen.findByRole("table", { name: "All" });
    expect(await within(table).findByText("Orders")).toBeVisible();
    expect(within(table).getByText("shop.draft_wide")).toBeVisible();
    expect(screen.getByRole("link", { name: "Datasets" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("marks a draft source reviewed through the engine's action", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: `/sources?id=${encodeURIComponent(SOURCE_ROWS[0].aggregateId)}`,
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Mark reviewed" }),
    );
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Mark reviewed" }),
    );
    await waitFor(() =>
      expect(commands.markReviewed).toHaveBeenCalledWith(
        SOURCE_ROWS[0].aggregateId,
      ),
    );
  });

  it("publishes a dataset through the engine's action", async () => {
    const { commands } = renderConsole(EDITOR, {
      path: `/datasets?id=${encodeURIComponent(DATASET_ROWS[0].aggregateId)}`,
    });
    await userEvent.click(
      await screen.findByRole("button", { name: "Publish" }),
    );
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Publish" }),
    );
    await waitFor(() =>
      expect(commands.publish).toHaveBeenCalledWith(
        DATASET_ROWS[0].aggregateId,
      ),
    );
  });
});
