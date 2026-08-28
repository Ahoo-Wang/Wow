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

import { AggregationDateUnit } from "@ahoo-wang/fetcher-wow";
import { describe, expect, it } from "vitest";
import { ExecutionFailedStatus } from "../../generated";
import { RetryConditions } from "../Failed/RetryConditions.ts";
import {
  bucketRetryRows,
  createEventTrendQueries,
  createPressureQuery,
  createPressureStatusQuery,
  createRecoverabilityQuery,
  createRetryHistogramQuery,
  createSnapshotSummaryQueries,
  createTrendWindow,
  mergeTrendRows,
  mergePressureRows,
} from "./analyticsQueries.ts";

describe("analyticsQueries", () => {
  it("builds all summary counts from one captured now", () => {
    const now = 1_787_932_800_000;
    const queries = createSnapshotSummaryQueries(now);

    expect(queries.actionableNow.filter).toEqual(
      RetryConditions.nextRetryCondition(now),
    );
    expect(queries.timedOut).toMatchObject({
      filter: {
        op: "AND",
        operands: [
          { op: "EQ", field: "state.status", value: "PREPARED" },
          {
            op: "LTE",
            field: "state.retryState.timeoutAt",
            value: now,
          },
        ],
      },
      metrics: [{ type: "COUNT", alias: "count" }],
    });
    expect(queries.unrecoverable.filter).toEqual(
      RetryConditions.unrecoverableCondition,
    );
  });

  it("groups pressure by error and the complete function identity", () => {
    expect(createPressureQuery()).toMatchObject({
      groupBy: [
        { type: "TERMS", field: "state.error.errorCode", alias: "errorCode" },
        {
          type: "TERMS",
          field: "state.function.contextName",
          alias: "contextName",
        },
        {
          type: "TERMS",
          field: "state.function.processorName",
          alias: "processorName",
        },
        { type: "TERMS", field: "state.function.name", alias: "functionName" },
        {
          type: "TERMS",
          field: "state.function.functionKind",
          alias: "functionKind",
        },
      ],
      metrics: [
        { type: "COUNT", alias: "currentCount" },
        {
          type: "NUMERIC",
          function: "MIN",
          expression: { type: "FIELD", field: "state.executeAt" },
          alias: "oldestExecuteAt",
        },
        {
          type: "NUMERIC",
          function: "MIN",
          expression: { type: "FIELD", field: "state.retryState.nextRetryAt" },
          alias: "nextRetryAt",
        },
      ],
      sort: [{ field: "currentCount", direction: "DESC" }],
      limit: 10,
    });
  });

  it("builds status counts only for the supplied pressure clusters", () => {
    expect(
      createPressureStatusQuery([
        {
          errorCode: "TEST_TIMEOUT",
          contextName: "billing",
          processorName: "OrderProcessor",
          functionName: "run",
          functionKind: "EVENT",
        },
      ]),
    ).toMatchObject({
      filter: {
        op: "OR",
        operands: [
          {
            op: "AND",
            operands: [
              {
                op: "EQ",
                field: "state.error.errorCode",
                value: "TEST_TIMEOUT",
              },
              {
                op: "EQ",
                field: "state.function.contextName",
                value: "billing",
              },
              {
                op: "EQ",
                field: "state.function.processorName",
                value: "OrderProcessor",
              },
              { op: "EQ", field: "state.function.name", value: "run" },
              {
                op: "EQ",
                field: "state.function.functionKind",
                value: "EVENT",
              },
            ],
          },
        ],
      },
      groupBy: [
        { type: "TERMS", field: "state.error.errorCode", alias: "errorCode" },
        {
          type: "TERMS",
          field: "state.function.contextName",
          alias: "contextName",
        },
        {
          type: "TERMS",
          field: "state.function.processorName",
          alias: "processorName",
        },
        { type: "TERMS", field: "state.function.name", alias: "functionName" },
        {
          type: "TERMS",
          field: "state.function.functionKind",
          alias: "functionKind",
        },
        { type: "TERMS", field: "state.status", alias: "status" },
      ],
      metrics: [{ type: "COUNT", alias: "statusCount" }],
    });
  });

  it("keeps same-status pressure clusters distinct for status merging", () => {
    const first = {
      errorCode: "TEST_TIMEOUT",
      contextName: "billing",
      processorName: "OrderProcessor",
      functionName: "run",
      functionKind: "EVENT",
    };
    const second = { ...first, processorName: "InvoiceProcessor" };

    expect(createPressureStatusQuery([first, second]).groupBy).toEqual([
      { type: "TERMS", field: "state.error.errorCode", alias: "errorCode" },
      {
        type: "TERMS",
        field: "state.function.contextName",
        alias: "contextName",
      },
      {
        type: "TERMS",
        field: "state.function.processorName",
        alias: "processorName",
      },
      { type: "TERMS", field: "state.function.name", alias: "functionName" },
      {
        type: "TERMS",
        field: "state.function.functionKind",
        alias: "functionKind",
      },
      { type: "TERMS", field: "state.status", alias: "status" },
    ]);
    expect(
      mergePressureRows(
        [
          {
            ...first,
            currentCount: 9,
            oldestExecuteAt: 1_000,
            nextRetryAt: 2_000,
          },
          {
            ...second,
            currentCount: 4,
            oldestExecuteAt: 3_000,
            nextRetryAt: 4_000,
          },
        ],
        [
          { ...first, status: ExecutionFailedStatus.FAILED, statusCount: 9 },
          { ...second, status: ExecutionFailedStatus.FAILED, statusCount: 4 },
        ],
      ),
    ).toMatchObject([
      { processorName: "OrderProcessor", failedCount: 9, preparedCount: 0 },
      { processorName: "InvoiceProcessor", failedCount: 4, preparedCount: 0 },
    ]);
  });

  it("rejects a pressure status query without clusters", () => {
    expect(() => createPressureStatusQuery([])).toThrow(
      "Pressure status query requires at least one cluster.",
    );
  });

  it("groups active snapshots by recoverability", () => {
    expect(createRecoverabilityQuery()).toMatchObject({
      filter: {
        op: "IN",
        field: "state.status",
        values: ["FAILED", "PREPARED"],
      },
      groupBy: [
        { type: "TERMS", field: "state.recoverable", alias: "recoverable" },
      ],
      metrics: [{ type: "COUNT", alias: "count" }],
    });
  });

  it("builds a bounded retry histogram for active snapshots", () => {
    expect(createRetryHistogramQuery()).toMatchObject({
      filter: {
        op: "IN",
        field: "state.status",
        values: ["FAILED", "PREPARED"],
      },
      groupBy: [
        {
          type: "HISTOGRAM",
          field: "state.retryState.retries",
          interval: 1,
          alias: "retries",
        },
      ],
      metrics: [{ type: "COUNT", alias: "count" }],
      limit: 1_000,
    });
  });

  it("merges status counts without colliding same-named functions", () => {
    const rows = [
      {
        errorCode: "TEST_TIMEOUT",
        contextName: "billing",
        processorName: "OrderProcessor",
        functionName: "run",
        functionKind: "EVENT",
        currentCount: 12,
        oldestExecuteAt: 1_000,
        nextRetryAt: 2_000,
      },
    ];
    const statuses = [
      {
        errorCode: "TEST_TIMEOUT",
        contextName: "billing",
        processorName: "OrderProcessor",
        functionName: "run",
        functionKind: "EVENT",
        status: ExecutionFailedStatus.FAILED,
        statusCount: 9,
      },
      {
        errorCode: "TEST_TIMEOUT",
        contextName: "billing",
        processorName: "OrderProcessor",
        functionName: "run",
        functionKind: "EVENT",
        status: ExecutionFailedStatus.PREPARED,
        statusCount: 3,
      },
    ];

    expect(mergePressureRows(rows, statuses)[0]).toMatchObject({
      currentCount: 12,
      failedCount: 9,
      preparedCount: 3,
    });
  });

  it("refuses a retry distribution that reaches the aggregation limit", () => {
    const rows = Array.from({ length: 1_000 }, (_, retries) => ({
      retries,
      count: 1,
    }));

    expect(bucketRetryRows(rows)).toEqual({ buckets: [], truncated: true });
  });

  it("buckets retry counts at the approved boundaries", () => {
    expect(
      bucketRetryRows([
        { retries: 0, count: 5 },
        { retries: 1, count: 4 },
        { retries: 2, count: 3 },
        { retries: 3, count: 2 },
        { retries: 5, count: 1 },
        { retries: 6, count: 7 },
      ]),
    ).toEqual({
      truncated: false,
      buckets: [
        { key: "0", count: 5 },
        { key: "1–2", count: 7 },
        { key: "3–5", count: 3 },
        { key: "6+", count: 7 },
      ],
    });
  });

  it.each([
    ["24h", 24, "HOUR"],
    ["7d", 7, "DAY"],
    ["30d", 30, "DAY"],
  ] as const)("aligns %s to exactly %i buckets", (range, size, unit) => {
    const window = createTrendWindow(
      range,
      new Date(2026, 7, 28, 10, 37).getTime(),
      "Asia/Shanghai",
    );

    expect(window.buckets).toHaveLength(size);
    expect(window.unit).toBe(unit);
    expect(window.start).toBe(window.buckets[0]);
  });

  it("filters event streams by root time and body event name", () => {
    const window = createTrendWindow(
      "7d",
      new Date(2026, 7, 28, 10, 37).getTime(),
      "Asia/Shanghai",
    );
    const query = createEventTrendQueries(window).succeeded;

    expect(query).toMatchObject({
      filter: {
        op: "AND",
        operands: expect.arrayContaining([
          { op: "GTE", field: "createTime", value: window.start },
          { op: "LT", field: "createTime", value: window.end },
          {
            op: "ELEMENT_MATCH",
            field: "body",
            predicate: {
              op: "EQ",
              field: "name",
              value: "execution_success_applied",
            },
          },
        ]),
      },
      groupBy: [
        {
          type: "DATE_HISTOGRAM",
          field: "createTime",
          alias: "bucket",
          unit: "DAY",
          timeZone: "Asia/Shanghai",
        },
      ],
      metrics: [{ type: "COUNT", alias: "streamCount" }],
      limit: 7,
    });
  });

  it("fills missing buckets with zero without treating missing series as zero", () => {
    const window = {
      buckets: [1_000, 2_000, 3_000],
      start: 1_000,
      end: 4_000,
      timeZone: "UTC",
      unit: AggregationDateUnit.HOUR,
    };
    const points = mergeTrendRows(window, {
      newFailures: [{ bucket: 1_000, streamCount: 2 }],
      prepared: [{ bucket: 2_000, streamCount: 3 }],
      retriedFailed: [],
      succeeded: [{ bucket: 3_000, streamCount: 1 }],
    });

    expect(points).toEqual([
      {
        bucket: 1_000,
        newFailures: 2,
        prepared: 0,
        retriedFailed: 0,
        succeeded: 0,
      },
      {
        bucket: 2_000,
        newFailures: 0,
        prepared: 3,
        retriedFailed: 0,
        succeeded: 0,
      },
      {
        bucket: 3_000,
        newFailures: 0,
        prepared: 0,
        retriedFailed: 0,
        succeeded: 1,
      },
    ]);
  });
});
