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

import type { Page } from "@playwright/test";
import {
  aggregate,
  executions,
  type Snapshot,
} from "./executionFailedService.ts";

/**
 * The moment the overview is read at, pinned in the page (`page.clock`) and
 * in the stub alike: a Saturday noon, eight days after the first execution,
 * so the default window — today and the six days before — leaves the first
 * two days' executions out.
 */
export const OVERVIEW_NOW = Date.parse("2026-09-26T12:00:00.000Z");

const HOUR = 3_600_000;

/**
 * The executions the overview counts: the shared 45, with a few made to
 * answer each of its questions differently — some still executing or not due
 * yet (their deadlines after the pinned moment), some retried past six times
 * — so no two backlog figures are the same number by accident.
 */
export function overviewExecutions(): Snapshot[] {
  return executions().map((snapshot, index) => {
    const state = snapshot.state as Record<string, unknown> & {
      retryState: Record<string, number>;
    };
    if (index % 6 === 1)
      state.retryState = {
        ...state.retryState,
        nextRetryAt: OVERVIEW_NOW + HOUR,
        timeoutAt: OVERVIEW_NOW + HOUR,
      };
    if (index % 11 === 4)
      state.retryState = { ...state.retryState, retries: 7 };
    // One more cluster, of a function the others lack, so a press has a
    // cluster of its own to open.
    if (index % 9 === 8)
      state.error = {
        ...(state.error as Record<string, unknown>),
        errorCode: "CONFLICT",
      };
    return snapshot;
  });
}

/** One event stream, as `…/event/aggregation` reads it. */
export type EventStream = {
  id: string;
  aggregateId: string;
  version: number;
  createTime: number;
  body: Array<{ id: string; name: string; bodyType: string }>;
};

const API = "me.ahoo.wow.compensation.api";

const EVENT_TYPES: Record<string, string> = {
  execution_failed_created: "ExecutionFailedCreated",
  compensation_prepared: "CompensationPrepared",
  execution_failed_applied: "ExecutionFailedApplied",
  execution_success_applied: "ExecutionSuccessApplied",
  retry_spec_applied: "RetrySpecApplied",
};

/**
 * Each execution's streams, one event apiece, as the service appends them:
 * created when it first failed; then a prepared and an outcome per retry, a
 * day apart — failed, or succeeded for a succeeded execution's last — and a
 * retry spec change on some, which no outcome counts.
 */
export function overviewStreams(documents: readonly Snapshot[]): EventStream[] {
  return documents.flatMap((snapshot, index) => {
    const state = snapshot.state as {
      status: string;
      retryState: { retries: number };
    };
    const names = ["execution_failed_created"];
    const retries = Math.min(state.retryState.retries, 3);
    for (let retry = 1; retry <= retries; retry++) {
      names.push("compensation_prepared");
      names.push(
        state.status === "SUCCEEDED" && retry === retries
          ? "execution_success_applied"
          : "execution_failed_applied",
      );
    }
    if (index % 5 === 2) names.push("retry_spec_applied");
    return names.map((name, version) => ({
      id: `${snapshot.aggregateId}-stream-${version + 1}`,
      aggregateId: snapshot.aggregateId,
      version: version + 1,
      createTime: snapshot.firstEventTime + version * 20 * HOUR,
      body: [
        {
          id: `${snapshot.aggregateId}-event-${version + 1}`,
          name,
          bodyType: `${API}.${EVENT_TYPES[name]}`,
        },
      ],
    }));
  });
}

/**
 * Stubs the compensation service's `execution_failed` event stream
 * aggregation over `streams`, as the snapshot's is stubbed: what the page
 * really sent, answered over the documents. Returns what was asked.
 */
export async function stubExecutionFailedEvents(
  page: Page,
  streams: readonly EventStream[],
  { now }: { now?: number } = {},
): Promise<unknown[]> {
  const asked: unknown[] = [];
  await page.route("**/execution_failed/event/aggregation", async (route) => {
    const query = route.request().postDataJSON();
    asked.push(query);
    try {
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(aggregate(streams, now, query)),
      });
    } catch (error) {
      await route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({
          errorCode: "IllegalArgument",
          errorMsg: error instanceof Error ? error.message : String(error),
        }),
      });
    }
  });
  return asked;
}
