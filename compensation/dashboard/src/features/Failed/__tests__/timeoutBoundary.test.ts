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

import { describe, expect, it } from "vitest";
import {
  AggregationDateUnit,
  type FilterExpression,
  RecoverableType,
} from "@ahoo-wang/wow-client";
import { ExecutionFailedStatus } from "../../../generated";
import {
  createSnapshotSummaryQuery,
  type TrendWindow,
} from "../../Analytics/analyticsQueries.ts";
import { getCompensationCapabilities } from "../compensationCapabilities.ts";
import { FindCategory } from "../FindCategory.ts";
import { RetryConditions } from "../RetryConditions.ts";

// The command side is the final decision boundary: RetryState.timeout() is
// `now > timeoutAt`. Every query that splits prepared executions into
// "executing" and "timed out" must agree with it, including at timeoutAt == now.

type Snapshot = Record<string, unknown>;

function read(snapshot: Snapshot, path: string): unknown {
  return path
    .split(".")
    .reduce<unknown>(
      (value, key) => (value as Record<string, unknown> | undefined)?.[key],
      snapshot,
    );
}

function matches(expression: FilterExpression, snapshot: Snapshot): boolean {
  const node = expression as {
    op: string;
    field?: string;
    value?: unknown;
    values?: unknown[];
    operands?: FilterExpression[];
  };
  const actual = node.field === undefined ? undefined : read(snapshot, node.field);
  switch (node.op) {
    case "AND":
      return node.operands!.every((operand) => matches(operand, snapshot));
    case "OR":
      return node.operands!.some((operand) => matches(operand, snapshot));
    case "EQ":
      return actual === node.value;
    case "IN":
      return node.values!.includes(actual);
    case "LT":
      return (actual as number) < (node.value as number);
    case "LTE":
      return (actual as number) <= (node.value as number);
    case "GT":
      return (actual as number) > (node.value as number);
    case "GTE":
      return (actual as number) >= (node.value as number);
    default:
      throw new Error(`Unsupported operator in boundary test: ${node.op}`);
  }
}

const timeoutAt = 1_735_000_120_000;

const prepared: Snapshot = {
  state: {
    status: ExecutionFailedStatus.PREPARED,
    recoverable: RecoverableType.RECOVERABLE,
    isRetryable: true,
    isBelowRetryThreshold: true,
    executeAt: timeoutAt - 120_000,
    retryState: { retryAt: timeoutAt - 120_000, nextRetryAt: 0, timeoutAt },
  },
};

function timedOutCount(now: number): FilterExpression {
  const window: TrendWindow = {
    buckets: [],
    end: timeoutAt,
    start: 0,
    timeZone: "UTC",
    unit: AggregationDateUnit.DAY,
  };
  const metric = createSnapshotSummaryQuery(now, window).metrics.find(
    ({ alias }) => alias === "timedOut",
  ) as { filter: FilterExpression };
  return metric.filter;
}

describe("timeout boundary follows the command side", () => {
  it.each([
    { label: "one millisecond before", now: timeoutAt - 1, timedOut: false },
    { label: "at timeoutAt", now: timeoutAt, timedOut: false },
    { label: "one millisecond after", now: timeoutAt + 1, timedOut: true },
  ])("treats a prepared execution $label as timedOut=$timedOut", ({ now, timedOut }) => {
    expect(matches(RetryConditions.executingCondition(now), prepared)).toBe(
      !timedOut,
    );
    expect(matches(RetryConditions.toRetryCondition(now), prepared)).toBe(
      timedOut,
    );
    expect(matches(RetryConditions.nextRetryCondition(now), prepared)).toBe(
      timedOut,
    );
    expect(
      matches(RetryConditions.categoryToCondition(FindCategory.Executing, now), prepared),
    ).toBe(!timedOut);
    expect(matches(timedOutCount(now), prepared)).toBe(timedOut);

    const state = prepared.state as Parameters<typeof getCompensationCapabilities>[0];
    expect(getCompensationCapabilities(state, now).canPrepare).toBe(timedOut);
  });
});
