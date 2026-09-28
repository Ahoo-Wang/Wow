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

import type {
  AggregationQuery,
  FilterExpression,
  FilterPagedQuery,
} from "@ahoo-wang/wow-client";
import type { ViewSource } from "@ahoo-wang/wow-view-engine";
import { matches, memorySource } from "@ahoo-wang/wow-view-engine/testing";
import type { Page, Route } from "@playwright/test";
import { stubNoDescriptors } from "./descriptorService.ts";

/** A snapshot of one failed execution, as `…/snapshot/paged` returns it. */
export type Snapshot = {
  aggregateId: string;
  firstEventTime: number;
  eventTime: number;
  state: Record<string, unknown>;
};

export type SnapshotQueries = {
  paged: Array<
    FilterPagedQuery & { pagination: { index: number; size: number } }
  >;
  aggregation: AggregationQuery[];
  /**
   * The IDs of every document each page's condition matched, in the order
   * asked — before paging, so a view compares whole.
   */
  matched: string[][];
};

export type StubOptions = {
  /**
   * The service's clock, for `BEFORE_NOW` and `AFTER_NOW`; the real one when
   * unset. A test comparing against the moment pins it, here and in the page
   * (`page.clock`).
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

/**
 * The service over `documents`, as the console's queries reach it: Wow's
 * semantics, answered by the view engine's in-memory source, on the service's
 * clock `now` — the real one when a test does not pin it. A test whose answer
 * depends on the moment pins it, here and in the page (`page.clock`).
 */
export function serviceOver(
  documents: readonly object[],
  now: number | undefined,
): ViewSource {
  return memorySource(documents as Record<string, unknown>[], {
    now: () => now ?? Date.now(),
  });
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
 * `paged` and `aggregation` — with no capability descriptor (a suite about
 * descriptors routes its own after this, `stubDescriptors`), over
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
  await stubNoDescriptors(page);
  await page.route("**/execution_failed/snapshot/paged", async (route) => {
    const query = route.request().postDataJSON();
    queries.paged.push(query);
    try {
      const page = await serviceOver(documents, now).paged(query);
      const filter: FilterExpression = query.filter ?? { op: "MATCH_ALL" };
      queries.matched.push(
        documents
          .filter((document) =>
            matches(document, filter, { now: () => now ?? Date.now() }),
          )
          .map(({ aggregateId }) => aggregateId),
      );
      await answer(route, page);
    } catch (error) {
      await refuse(route, error);
    }
  });
  await page.route(
    "**/execution_failed/snapshot/aggregation",
    async (route) => {
      const query = route.request().postDataJSON();
      queries.aggregation.push(query);
      try {
        await answer(route, await serviceOver(documents, now).aggregate(query));
      } catch (error) {
        await refuse(route, error);
      }
    },
  );
  return queries;
}

/** One command the page sent, as the stub received it. */
export type SentCommand = {
  id: string;
  command: string;
  waitStage: string | null;
  body: unknown;
};

export type CommandStubOptions = {
  /** Executions whose commands the service refuses, with its reason. */
  refuse?: ReadonlyMap<string, string>;
  /**
   * Held until it settles, so a test can see a command in flight; every
   * command waits on it before it is answered.
   */
  hold?: Promise<void>;
  /**
   * The service's clock, stamped on an execution a prepare starts. Unset, it
   * is the real one; a test that pins the page's clock pins this to the same
   * moment, or a prepared execution retries in the page's future.
   */
  now?: number;
};

/** The execution timeout of a prepared execution, in milliseconds. */
const EXECUTION_TIMEOUT = 120_000;

/**
 * Stubs the `execution_failed` commands the workbench sends —
 * `prepare_compensation`, `force_prepare_compensation`, `mark_recoverable`,
 * and the detail's `apply_retry_spec` and `change_function` — over the same
 * `documents` the query stub reads, so a command the service takes shows in
 * the next page: a prepared execution is `PREPARED` with a retry deadline
 * in the future. A refused one answers 400 with the command result Wow
 * answers, whose message the page shows.
 */
export async function stubExecutionFailedCommands(
  page: Page,
  documents: Snapshot[],
  { refuse = new Map(), hold, now: clock }: CommandStubOptions = {},
): Promise<SentCommand[]> {
  const sent: SentCommand[] = [];
  await page.route(
    /\/execution_failed\/([^/]+)\/(prepare_compensation|force_prepare_compensation|mark_recoverable|apply_retry_spec|change_function)$/,
    async (route) => {
      const request = route.request();
      const [, id, command] =
        /\/execution_failed\/([^/]+)\/([^/]+)$/.exec(request.url()) ?? [];
      const body = request.postDataJSON() as Record<string, unknown> | null;
      sent.push({
        id,
        command,
        waitStage: request.headers()["command-wait-stage"] ?? null,
        body,
      });
      await hold;
      const reason = refuse.get(id);
      if (reason !== undefined) {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify({
            id: `${id}-result`,
            aggregateId: id,
            errorCode: "IllegalState",
            errorMsg: reason,
          }),
        });
        return;
      }
      const document = documents.find(({ aggregateId }) => aggregateId === id);
      if (document) {
        const state = document.state as Record<string, unknown> & {
          retryState: Record<string, number>;
        };
        if (command === "mark_recoverable")
          state.recoverable = body?.recoverable;
        else if (command === "apply_retry_spec") state.retrySpec = body;
        else if (command === "change_function") state.function = body;
        else {
          const now = clock ?? Date.now();
          state.status = "PREPARED";
          state.retryState = {
            ...state.retryState,
            retries: state.retryState.retries + 1,
            retryAt: now,
            timeoutAt: now + EXECUTION_TIMEOUT,
          };
          state.isBelowRetryThreshold = state.retryState.retries < 3;
        }
      }
      await answer(route, {
        id: `${id}-result`,
        aggregateId: id,
        errorCode: "Ok",
        errorMsg: "",
        stage: "SNAPSHOT",
      });
    },
  );
  return sent;
}
