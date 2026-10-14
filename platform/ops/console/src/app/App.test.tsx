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
import { MemoryViewStore } from "@ahoo-wang/wow-view-engine";
import { memorySource } from "@ahoo-wang/wow-view-engine/testing";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { I18nProvider, type Locale } from "@/i18n.tsx";
import { ROUTES } from "@/router.tsx";
import { CallerStore, type OpsCaller } from "@/services/caller.ts";
import type { OpsServices } from "@/services/ops.ts";
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
      failedAt: Date.parse("2026-10-01T08:00:00Z"),
      recordedAt: Date.parse("2026-10-01T08:00:01Z"),
      retries: 3,
      retrySpec: {
        maxRetries: 3,
        minBackoff: "PT1M",
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
      failedAt: Date.parse("2026-10-01T09:00:00Z"),
      status: "SCHEDULED",
    },
  },
];

function console_(
  caller: OpsCaller | null,
  { locale = "en" as Locale, path = "/parked" } = {},
) {
  const callers = new CallerStore(null);
  if (caller) callers.set(caller);
  const commands = {
    resume: vi.fn(async () => ({})),
    skip: vi.fn(async () => ({})),
    markRecoverable: vi.fn(async () => ({})),
    applyRetrySpec: vi.fn(async () => ({})),
  };
  const services: OpsServices = {
    callers,
    fetcher: new Fetcher({ baseURL: "" }),
    commands,
    engine: createOpsEngine({
      source: memorySource(ENTRIES),
      store: new MemoryViewStore(),
    }),
  };
  const router = createMemoryRouter(ROUTES, { initialEntries: [path] });
  render(
    <I18nProvider initial={locale}>
      <ServicesProvider services={services}>
        <RouterProvider router={router} />
      </ServicesProvider>
    </I18nProvider>,
  );
  return { callers, commands, router };
}

const OPERATOR: OpsCaller = {
  user: "alice",
  tenant: "(0)",
  roles: ["ops.operator"],
};

describe("the console", () => {
  it("asks for a caller first, then lists the entries that need attention", async () => {
    const { callers } = console_(null);
    expect(
      screen.getByText(/Name a caller to read the parked entries/),
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

  it("sends an unknown page to the parked entries", async () => {
    const { router } = console_(OPERATOR, { path: "/nowhere" });
    await screen.findByRole("table", { name: "Needs attention" });
    expect(router.state.location.pathname).toBe("/parked");
  });
});
