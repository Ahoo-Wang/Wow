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
import type { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import {
  MemoryViewStore,
  type DataViewDefinition,
  type DatasetResource,
} from "@ahoo-wang/wow-view-engine";
import { memorySource } from "@ahoo-wang/wow-view-engine/testing";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { vi } from "vitest";
import { I18nProvider, type Locale } from "@/i18n.tsx";
import { ROUTES } from "@/router.tsx";
import { CallerStore, type CatalogCaller } from "@/services/caller.ts";
import type { CatalogServices } from "@/services/catalog.ts";
import type { CatalogCommands } from "@/views/catalogCommands.ts";
import { createCatalogEngine } from "@/views/engine.ts";
import type { ReferenceActions } from "@/views/referenceActions.ts";
import { ServicesProvider } from "./services.tsx";

export const SOURCE_ROWS = [
  {
    aggregateId: "default:shop:orders",
    state: {
      connection: "default",
      database: "shop",
      table: "orders",
      engine: "clickhouse",
      tableEngine: "MergeTree",
      tableUuid: "u-1",
      kind: "ANALYST",
      status: "DRAFT",
      approved: false,
      missing: false,
      platform: false,
      tenantColumn: "tenant_id",
      columns: [
        { name: "id", type: "String" },
        { name: "tenant_id", type: "String" },
        { name: "buyer", type: "String" },
        { name: "customer_id", type: "String" },
      ],
      references: [
        { path: "customer_id", context: "crm", aggregate: "customer" },
      ],
      sensitivity: [{ path: "buyer", level: "display", keepPrefix: 1 }],
      claimed: [],
      pending: [],
      refusals: [],
      enrichmentLost: false,
    },
  },
  {
    aggregateId: "default:wow:orders_state",
    state: {
      connection: "default",
      database: "wow",
      table: "orders_state",
      engine: "clickhouse",
      tableEngine: "ReplacingMergeTree",
      tableUuid: "u-2",
      kind: "WOW",
      status: "ADOPTED",
      approved: true,
      missing: false,
      platform: false,
      tenantColumn: "tenant_id",
      columns: [],
      references: [],
      sensitivity: [
        { event: "OrderCreated", path: "address", level: "confidential" },
      ],
      claimed: [],
      pending: ["OrderCreated/phone"],
      refusals: [],
      enrichmentLost: false,
      enrichment: {
        context: "shop",
        aggregate: "order",
        writer: "wow_sink",
        writtenAt: "2026-10-01T08:00:00Z",
        fingerprint: "f",
        ownership: "TENANT",
        role: "STATE",
        service: "order-service",
      },
    },
  },
];

export const DEFINITION = {
  subject: "o",
  sources: [{ alias: "o", sourceId: "default:shop:orders" }],
  columns: [{ name: "id", from: "o.id" }],
  identity: "id",
};

export const DATASET_ROWS = [
  {
    aggregateId: "shop.order_wide",
    state: {
      context: "shop",
      name: "order_wide",
      title: "Orders",
      status: "NEEDS_REVISION",
      current: 1,
      stillServing: true,
      dropped: false,
      definition: DEFINITION,
      problems: ["default:shop:orders lost its column id."],
      requestReasons: [],
      sources: ["default:shop:orders"],
      versions: [
        {
          definition: DEFINITION,
          artifact: {
            context: "shop",
            name: "order_wide",
            database: "wow_datasets",
            view: "ds_shop_order_wide_v1",
            version: 1,
            ddl: "CREATE VIEW wow_datasets.ds_shop_order_wide_v1 AS SELECT o.id AS id FROM shop.orders AS o",
            sources: ["default:shop:orders"],
            scope: { tenant: "tenant_id" },
            fields: [
              {
                name: "id",
                reference: { context: "shop", aggregate: "order" },
              },
            ],
          },
        },
      ],
    },
  },
  {
    aggregateId: "shop.draft_wide",
    state: {
      context: "shop",
      name: "draft_wide",
      status: "DRAFT",
      current: 0,
      stillServing: false,
      dropped: false,
      definition: DEFINITION,
      requested: DEFINITION,
      problems: [],
      requestReasons: [],
      sources: [],
      versions: [],
    },
  },
];

/** A served dataset's definition, as `DatasetResources` registers one. */
export const ORDER_ROWS: DataViewDefinition = {
  id: "shop.order_wide",
  kind: "data",
  source: "shop.order_wide",
  title: "Orders",
  recordNoun: "order",
  fields: [
    { name: "id", label: "Order", kind: "string", sortable: true },
    { name: "city", label: "City", kind: "string", sortable: true },
    { name: "customer", label: "Customer", kind: "string", sortable: true },
  ],
  record: { rowKey: "id", paging: "paged", layouts: ["table"] },
  views: [
    {
      id: "all",
      title: "All orders",
      config: {
        kind: "record",
        filter: { op: "and", children: [] },
        filterMode: "simple",
        refresh: { interval: null },
        sort: [{ field: "id", direction: "asc" }],
        pageSize: 20,
        layout: "table",
        summaries: [],
        table: {
          columns: [{ field: "id" }, { field: "city" }, { field: "customer" }],
        },
        card: { title: "id", fields: ["city"] },
      },
    },
  ],
};

/** The row actions of the served orders: a customer the order refers to may be suspended. */
export const ORDER_ACTIONS: ReferenceActions = {
  dataset: "shop.order_wide",
  fields: [
    {
      field: "customer",
      context: "crm",
      aggregate: "customer",
      commands: [
        {
          name: "SuspendCustomer",
          description: "Suspend the customer.",
          inputs: [
            { name: "reason", type: "string", required: true },
            { name: "days", type: "integer", required: false },
          ],
        },
      ],
    },
  ],
};

export function commandsStub(): {
  [K in keyof CatalogCommands]: ReturnType<typeof vi.fn>;
} & CatalogCommands {
  const done = () => vi.fn(async () => ({}));
  return {
    draftSource: done(),
    reviseSource: done(),
    reviewSensitivity: done(),
    markReviewed: done(),
    approveSource: done(),
    approveSensitivityChange: done(),
    draftDataset: done(),
    reviseDataset: done(),
    publish: done(),
    approvePublication: done(),
    refusePublication: done(),
    rollback: done(),
    retire: done(),
  };
}

export const EDITOR: CatalogCaller = {
  user: "alice",
  tenant: "(platform)",
  roles: ["analytics.catalog.editor"],
};

/** The console's engine over memory sources, with the orders dataset when one is `served`. */
export function catalogEngine(served: readonly DatasetResource[] = []) {
  const engine = createCatalogEngine({
    sources: memorySource(SOURCE_ROWS),
    datasets: memorySource(DATASET_ROWS),
    store: new MemoryViewStore(),
  });
  if (served.length)
    engine.register({
      definition: ORDER_ROWS,
      source: memorySource([
        { id: "o-1", city: "Hangzhou", customer: "c-1" },
        { id: "o-2", city: "Shanghai", customer: null },
      ]),
    });
  return engine;
}

/** The console over memory sources, as `caller`, on `path`. */
export function renderConsole(
  caller: CatalogCaller | null,
  {
    locale = "en" as Locale,
    path = "/sources",
    tokens = null as TokenStorage | null,
    served = [] as DatasetResource[],
    engine = catalogEngine(served),
  } = {},
) {
  const callers = new CallerStore(null, tokens);
  if (caller) callers.set(caller);
  const commands = commandsStub();
  const refresh = vi.fn(async () => ({ datasets: served, duplicates: [] }));
  const referenceActionsOf = vi.fn(async (dataset: string) => {
    if (dataset !== ORDER_ACTIONS.dataset) throw new Error("No row actions.");
    return ORDER_ACTIONS;
  });
  const send = vi.fn(async () => ({ status: "succeeded" }));
  const services: CatalogServices = {
    callers,
    fetcher: new Fetcher({ baseURL: "" }),
    commands,
    engine,
    datasets: { refresh },
    references: { actions: referenceActionsOf, send },
  };
  const router = createMemoryRouter(ROUTES, { initialEntries: [path] });
  render(
    <I18nProvider initial={locale}>
      <ServicesProvider services={services}>
        <RouterProvider router={router} />
      </ServicesProvider>
    </I18nProvider>,
  );
  return { callers, commands, router, refresh, send, engine };
}
