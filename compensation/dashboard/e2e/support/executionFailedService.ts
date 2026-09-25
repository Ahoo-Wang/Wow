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

import type { Page, Route } from "@playwright/test";

/** A snapshot of one failed execution, as `…/snapshot/paged` returns it. */
export type Snapshot = {
  aggregateId: string;
  firstEventTime: number;
  eventTime: number;
  state: Record<string, unknown>;
};

type Filter = {
  op: string;
  field?: string;
  value?: unknown;
  values?: unknown[];
  operands?: Filter[];
  query?: string;
  fields?: string[];
  offset?: string;
};

type Group = { type: string; field: string; alias: string; unit?: string };
type Metric = {
  alias: string;
  type: string;
  function?: string;
  expression?: { type: string; field?: string };
};

export type SnapshotQueries = {
  paged: Array<{ filter: Filter; pagination: { index: number; size: number } }>;
  aggregation: Array<{ filter?: Filter; groupBy?: Group[] }>;
  /**
   * Every page asked for, by the endpoint that asked (`paged` is the view
   * engine's, `paged/state` the old queues'), with the IDs of every document
   * its condition matched — before paging, so two queues compare whole.
   */
  matched: Array<{ endpoint: "paged" | "paged/state"; ids: string[] }>;
};

export type StubOptions = {
  /**
   * The service's clock, for `BEFORE_NOW` and `AFTER_NOW`. Unset, either
   * operator fails the request: a test comparing against the moment pins
   * it, here and in the page (`page.clock`).
   */
  now?: number;
};

const START = Date.parse("2026-09-18T08:00:00.000Z");
const DAY = 86_400_000;

const PROCESSORS = [
  ["OrderSaga", "onOrderCreated", "Inventory refused the reservation."],
  ["PaymentSaga", "onPaymentRequested", "Payment gateway timed out."],
  ["InventorySaga", "onStockChanged", "Warehouse returned 503."],
] as const;

const STATUSES = ["FAILED", "PREPARED", "SUCCEEDED"] as const;
const RECOVERABILITY = ["RECOVERABLE", "UNKNOWN", "UNRECOVERABLE"] as const;

/**
 * 45 executions over three processors, so the default page of 20 has two
 * more pages behind it and a processor or an error narrows the rows.
 */
export function executions(count = 45): Snapshot[] {
  return Array.from({ length: count }, (_, index) => {
    const [processorName, name, errorMsg] = PROCESSORS[index % 3];
    const id = `EF-${String(index + 1).padStart(2, "0")}`;
    const eventTime = START + (index % 5) * DAY + index * 60_000;
    const status = STATUSES[index % 4 === 3 ? 2 : index % 2];
    const retries = index % 4;
    return {
      aggregateId: id,
      firstEventTime: eventTime - 3_600_000,
      eventTime,
      state: {
        id,
        status,
        recoverable: RECOVERABILITY[index % 3],
        isRetryable: status !== "SUCCEEDED",
        isBelowRetryThreshold: retries < 3,
        executeAt: eventTime,
        function: {
          contextName: "order-service",
          processorName,
          name,
          functionKind: "EVENT",
        },
        eventId: {
          id: `${id}-event`,
          version: 1,
          aggregateId: {
            contextName: "order-service",
            aggregateName: "order",
            aggregateId: `order-${id}`,
          },
        },
        error: {
          errorCode: `${processorName.toUpperCase()}_FAILED`,
          errorMsg,
          stackTrace: `at ${processorName}.${name}(${processorName}.kt:42)`,
        },
        retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
        retryState: {
          retries,
          retryAt: eventTime,
          nextRetryAt: eventTime + 180_000,
          timeoutAt: eventTime + 120_000,
        },
      },
    };
  });
}

function read(document: Snapshot, field: string): unknown {
  return field
    .split(".")
    .reduce<unknown>(
      (value, key) =>
        value && typeof value === "object"
          ? (value as Record<string, unknown>)[key]
          : undefined,
      document,
    );
}

/** `value < bound`, `value > bound` and the rest, on numbers alone. */
function compared(value: unknown, bound: unknown, op: string): boolean {
  if (typeof value !== "number" || typeof bound !== "number") return false;
  switch (op) {
    case "LT":
      return value < bound;
    case "LTE":
      return value <= bound;
    case "GT":
      return value > bound;
    default:
      return value >= bound;
  }
}

/** The service's clock for a now-relative condition, which only `PT0S` is. */
function serviceNow(filter: Filter, now: number | undefined): number {
  if (now === undefined)
    throw new Error(`${filter.op} needs the stub's clock pinned`);
  if ((filter.offset ?? "PT0S") !== "PT0S")
    throw new Error(`Unsupported ${filter.op} offset ${filter.offset}`);
  return now;
}

/** Answers what the console asks of these documents, the way Wow does. */
function matches(
  document: Snapshot,
  filter: Filter,
  now: number | undefined,
): boolean {
  const value = filter.field ? read(document, filter.field) : undefined;
  const each = (operand: Filter) => matches(document, operand, now);
  switch (filter.op) {
    case "MATCH_ALL":
      return true;
    case "AND":
      return (filter.operands ?? []).every(each);
    case "OR":
      return (filter.operands ?? []).some(each);
    case "NOR":
      return !(filter.operands ?? []).some(each);
    case "EQ":
      return value === filter.value;
    case "NE":
      return value !== filter.value;
    case "IN":
      return (filter.values ?? []).includes(value);
    case "NOT_IN":
      return !(filter.values ?? []).includes(value);
    case "IS_NULL":
      return value === null || value === undefined;
    case "IS_NOT_NULL":
      return value !== null && value !== undefined;
    case "LT":
    case "LTE":
    case "GT":
    case "GTE":
      return compared(value, filter.value, filter.op);
    // Strict both ways, on the service's clock (Wow N6).
    case "BEFORE_NOW":
      return compared(value, serviceNow(filter, now), "LT");
    case "AFTER_NOW":
      return compared(value, serviceNow(filter, now), "GT");
    case "SEARCH": {
      const phrase = (filter.query ?? "").toLowerCase();
      return (filter.fields ?? []).some((field) =>
        String(read(document, field) ?? "")
          .toLowerCase()
          .includes(phrase),
      );
    }
  }
  // An operator this stub does not know fails the request, so a test sees
  // it rather than a plausible wrong answer.
  throw new Error(`Unsupported filter operator ${filter.op}`);
}

function compare(a: unknown, b: unknown): number {
  if (a === b) return 0;
  if (a === undefined || a === null) return -1;
  if (b === undefined || b === null) return 1;
  return (a as number | string) < (b as number | string) ? -1 : 1;
}

function sorted<T>(
  rows: T[],
  sort: Array<{ field: string; direction: string }> | undefined,
  valueOf: (row: T, field: string) => unknown,
): T[] {
  return [...rows].sort((left, right) => {
    for (const { field, direction } of sort ?? []) {
      const order = compare(valueOf(left, field), valueOf(right, field));
      if (order !== 0) return direction === "DESC" ? -order : order;
    }
    return 0;
  });
}

function metricOf(rows: Snapshot[], metric: Metric): number | null {
  if (metric.type === "COUNT") return rows.length;
  const field = metric.expression?.field;
  if (metric.type !== "NUMERIC" || !field)
    throw new Error(`Unsupported metric ${JSON.stringify(metric)}`);
  const values = rows.map((row) => Number(read(row, field)));
  if (values.length === 0) return null;
  switch (metric.function) {
    case "SUM":
      return values.reduce((sum, each) => sum + each, 0);
    case "AVG":
      return values.reduce((sum, each) => sum + each, 0) / values.length;
    case "MIN":
      return Math.min(...values);
    case "MAX":
      return Math.max(...values);
  }
  throw new Error(`Unsupported metric function ${metric.function}`);
}

function keyOf(document: Snapshot, group: Group): unknown {
  const value = read(document, group.field);
  if (group.type === "TERMS") return value;
  if (group.type === "DATE_HISTOGRAM" && group.unit === "DAY")
    return Math.floor(Number(value) / DAY) * DAY;
  throw new Error(`Unsupported group ${JSON.stringify(group)}`);
}

function aggregate(
  documents: Snapshot[],
  now: number | undefined,
  query: {
    filter?: Filter;
    groupBy?: Group[];
    metrics: Metric[];
    sort?: Array<{ field: string; direction: string }>;
    limit?: number;
  },
) {
  const rows = documents.filter((document) =>
    matches(document, query.filter ?? { op: "MATCH_ALL" }, now),
  );
  const groups = query.groupBy ?? [];
  const buckets = new Map<string, { keys: unknown[]; rows: Snapshot[] }>();
  for (const row of rows) {
    const keys = groups.map((group) => keyOf(row, group));
    const id = JSON.stringify(keys);
    const bucket = buckets.get(id) ?? { keys, rows: [] };
    bucket.rows.push(row);
    buckets.set(id, bucket);
  }
  if (groups.length === 0 && buckets.size === 0)
    buckets.set("[]", { keys: [], rows: [] });
  const answer = [...buckets.values()].map(({ keys, rows: members }) => {
    const out: Record<string, unknown> = {};
    groups.forEach((group, index) => (out[group.alias] = keys[index]));
    for (const metric of query.metrics)
      out[metric.alias] = metricOf(members, metric);
    return out;
  });
  return sorted(answer, query.sort, (row, alias) => row[alias]).slice(
    0,
    query.limit ?? 100,
  );
}

async function answer(route: Route, body: unknown) {
  await route.fulfill({
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function refuse(route: Route, error: unknown) {
  await route.fulfill({
    status: 400,
    contentType: "application/json",
    body: JSON.stringify({
      errorCode: "IllegalArgument",
      errorMsg: error instanceof Error ? error.message : String(error),
    }),
  });
}

/**
 * Stubs the compensation service's `execution_failed` snapshot queries —
 * `paged`, the old queues' `paged/state` and `aggregation` — over
 * `documents`, filtering, sorting, paging and grouping what the page really
 * sent. Returns what was asked, so a test can say which query a control
 * sent.
 */
export async function stubExecutionFailedService(
  page: Page,
  documents: Snapshot[] = executions(),
  { now }: StubOptions = {},
): Promise<SnapshotQueries> {
  const queries: SnapshotQueries = { paged: [], aggregation: [], matched: [] };
  const pageOf = async (
    route: Route,
    endpoint: "paged" | "paged/state",
    shape: (document: Snapshot) => unknown,
  ) => {
    const query = route.request().postDataJSON();
    if (endpoint === "paged") queries.paged.push(query);
    try {
      const rows = documents.filter((document) =>
        matches(document, query.filter ?? { op: "MATCH_ALL" }, now),
      );
      queries.matched.push({
        endpoint,
        ids: rows.map(({ aggregateId }) => aggregateId),
      });
      const { index, size } = query.pagination ?? { index: 1, size: 10 };
      const list = sorted(rows, query.sort, read).slice(
        (index - 1) * size,
        index * size,
      );
      await answer(route, { total: rows.length, list: list.map(shape) });
    } catch (error) {
      await refuse(route, error);
    }
  };
  await page.route("**/execution_failed/snapshot/paged", (route) =>
    pageOf(route, "paged", (document) => document),
  );
  // The old queues read the states alone.
  await page.route("**/execution_failed/snapshot/paged/state", (route) =>
    pageOf(route, "paged/state", (document) => document.state),
  );
  await page.route(
    "**/execution_failed/snapshot/aggregation",
    async (route) => {
      const query = route.request().postDataJSON();
      queries.aggregation.push(query);
      try {
        await answer(route, aggregate(documents, now, query));
      } catch (error) {
        await refuse(route, error);
      }
    },
  );
  return queries;
}
