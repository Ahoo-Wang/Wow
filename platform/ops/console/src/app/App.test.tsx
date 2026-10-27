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

import { Fetcher } from "@ahoo-wang/fetcher";
import { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import { MemoryViewStore } from "@ahoo-wang/wow-view-engine";
import { memorySource } from "@ahoo-wang/wow-view-engine/testing";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, type Locale } from "@/i18n.tsx";
import { ROUTES } from "@/router.tsx";
import { CallerStore, type OpsCaller } from "@/services/caller.ts";
import type { OpsServices } from "@/services/ops.ts";
import { accessToken } from "@/services/testing.ts";
import { createOpsEngine } from "@/views/engine.ts";
import { ServicesProvider } from "./services.tsx";

const ENTRIES = [
  {
    aggregateId: "payment-service:Saga.onEvent:shop.payment:(0):p-1",
    state: {
      service: "payment-service",
      context: "shop",
      subscription: "Saga.onEvent",
      aggregate: {
        context: "shop",
        name: "payment",
        tenantId: "(0)",
        id: "p-1",
      },
      fromVersion: 2,
      reason: "The gateway is unreachable.",
      errorType: "The gateway is unreachable.",
      failedAt: Date.parse("2026-10-01T08:00:00Z"),
      recordedAt: Date.parse("2026-10-01T08:00:01Z"),
      retries: 3,
      retrySpec: {
        maxRetries: 3,
        initialBackoff: "PT1M",
        executionTimeout: "PT2M",
      },
      recoverable: "UNKNOWN",
      status: "EXHAUSTED",
    },
  },
  {
    aggregateId: "payment-service:Saga.onEvent:shop.payment:(0):p-2",
    state: {
      service: "payment-service",
      context: "shop",
      subscription: "Saga.onEvent",
      aggregate: {
        context: "shop",
        name: "payment",
        tenantId: "(0)",
        id: "p-2",
      },
      reason: "Retrying.",
      errorType: "Retrying.",
      failedAt: Date.parse("2026-10-01T09:00:00Z"),
      status: "SCHEDULED",
    },
  },
];

function console_(
  caller: OpsCaller | null,
  {
    locale = "en" as Locale,
    path = "/parked",
    tokens = null as TokenStorage | null,
  } = {},
) {
  const callers = new CallerStore(null, tokens);
  if (caller) callers.set(caller);
  const commands = {
    resume: vi.fn(async () => ({})),
    skip: vi.fn(async () => ({})),
    markRecoverable: vi.fn(async () => ({})),
    applyRetrySpec: vi.fn(async () => ({})),
  };
  const history = vi.fn(async (id: string) => [
    {
      aggregateId: id,
      tenantId: "(0)",
      version: 1,
      commitTime: Date.parse("2026-10-01T08:00:02Z"),
      commandId: "c-1",
      requestId: "r-1",
      commandType: "ops.parked.RecordParked",
      events: [
        {
          name: "ops.parked.ParkedRecorded",
          revision: 1,
          body: { entry: ENTRIES[0].state },
        },
      ],
    },
  ]);
  const services: OpsServices = {
    callers,
    fetcher: new Fetcher({ baseURL: "" }),
    commands,
    engine: createOpsEngine({
      source: memorySource(ENTRIES),
      store: new MemoryViewStore(),
    }),
    history,
  };
  const router = createMemoryRouter(ROUTES, { initialEntries: [path] });
  render(
    <I18nProvider initial={locale}>
      <ServicesProvider services={services}>
        <RouterProvider router={router} />
      </ServicesProvider>
    </I18nProvider>,
  );
  return { callers, commands, history, router };
}

const OPERATOR: OpsCaller = {
  user: "alice",
  tenant: "(0)",
  roles: ["ops.operator"],
};

describe("the console", () => {
  afterEach(() => localStorage.clear());

  it("asks for a caller first, then lists the entries that need attention", async () => {
    const { callers } = console_(null);
    expect(
      screen.getByText(/Sign in to read the parked entries/),
    ).toBeVisible();
    await userEvent.type(screen.getByLabelText("User"), "alice");
    await userEvent.click(
      screen.getByRole("button", { name: "Use this caller" }),
    );
    expect(callers.get()).toEqual(OPERATOR);
    const table = await screen.findByRole("table", { name: "Needs attention" });
    expect(
      await within(table).findByText("The gateway is unreachable."),
    ).toBeVisible();
    expect(within(table).queryByText("Retrying.")).toBeNull();
    expect(screen.getByRole("link", { name: "Caller: alice" })).toBeVisible();
  });

  it("signs in with a CoSec access token, and out again", async () => {
    const { callers, router } = console_(null, { tokens: new TokenStorage() });
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
      `Bearer ${accessToken({ sub: "alice", roles: ["ops.operator"] })}`,
    );
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(callers.get()).toEqual({ ...OPERATOR, via: "cosec" });
    expect(
      await screen.findByRole("table", { name: "Needs attention" }),
    ).toBeVisible();

    await router.navigate("/caller");
    expect(
      await screen.findByText("Signed in as alice with a CoSec access token."),
    ).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(callers.get()).toBeNull();
    expect(router.state.location.pathname).toBe("/parked");
  });

  it("says a caller without ops.operator only reads", async () => {
    console_({ user: "victor", tenant: "(0)", roles: ["viewer"] });
    expect(
      await screen.findByText(/This caller has no ops.operator role/),
    ).toBeVisible();
  });

  it("speaks Chinese", async () => {
    console_(OPERATOR, { locale: "zh-CN" });
    expect(
      await screen.findByRole("table", { name: "需要处理" }),
    ).toBeVisible();
    expect(screen.getAllByText("搁置条目").length).toBeGreaterThan(0);
  });

  it("changes and clears the caller on its own page", async () => {
    const { callers, router } = console_(OPERATOR, { path: "/caller" });
    const roles = screen.getByLabelText("Roles");
    await userEvent.clear(roles);
    await userEvent.type(roles, "viewer");
    await userEvent.click(
      screen.getByRole("button", { name: "Use this caller" }),
    );
    expect(callers.get()?.roles).toEqual(["viewer"]);
    expect(router.state.location.pathname).toBe("/parked");

    await router.navigate("/caller");
    await userEvent.click(await screen.findByRole("button", { name: "Clear" }));
    expect(callers.get()).toBeNull();
  });

  it("sends an unknown page to the overview", async () => {
    const { router } = console_(OPERATOR, { path: "/nowhere" });
    expect(
      await screen.findByRole("heading", { name: "Overview", level: 1 }),
    ).toBeVisible();
    expect(router.state.location.pathname).toBe("/overview");
  });

  it("opens on the overview board, which asks for a caller first", async () => {
    console_(null, { path: "/" });
    expect(
      screen.getByText(/Sign in to read the parked entries/),
    ).toBeVisible();
  });

  it("draws the overview's panels over the entries", async () => {
    console_(OPERATOR, { path: "/overview" });
    expect(
      await screen.findByRole("heading", { name: "Overview", level: 1 }),
    ).toBeVisible();
    for (const panel of [
      "All parked",
      "Failure clusters — top 10",
      "Top failing subscriptions",
      "Needing attention",
    ])
      expect((await screen.findAllByText(panel))[0]).toBeVisible();
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("reads an entry's cause and its history in its detail", async () => {
    const { history } = console_(OPERATOR, {
      path: `/parked?id=${encodeURIComponent(ENTRIES[0].aggregateId)}`,
    });
    const timeline = await screen.findByTestId("parked-history");
    expect(await within(timeline).findByText("Parked")).toBeVisible();
    expect(history).toHaveBeenCalledWith(ENTRIES[0].aggregateId);
    expect(screen.getByTestId("parked-failure")).toHaveTextContent(
      "The gateway is unreachable.",
    );
  });
});
