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
  const snapshotWindow = {
    buckets: [1_787_328_000_000],
    end: 1_787_932_800_000,
    start: 1_787_328_000_000,
    timeZone: "Asia/Shanghai",
    unit: AggregationDateUnit.DAY,
  };

  function expectSnapshotWindow(query: { filter?: unknown }) {
    expect(JSON.stringify(query.filter)).toContain('"field":"state.executeAt"');
    expect(query.filter).toMatchObject({
      op: "AND",
      operands: expect.arrayContaining([
        {
          op: "GTE",
          field: "state.executeAt",
          value: snapshotWindow.start,
        },
        {
          op: "LT",
          field: "state.executeAt",
          value: snapshotWindow.end,
        },
      ]),
    });
  }

  it("builds all summary counts from one captured now", () => {
    const now = 1_787_932_800_000;
    const queries = createSnapshotSummaryQueries(now, snapshotWindow);

    expect(JSON.stringify(queries.actionableNow.filter)).toContain(
      JSON.stringify(RetryConditions.nextRetryCondition(now)),
    );
    expect(JSON.stringify(queries.timedOut.filter)).toContain(
      JSON.stringify({
        op: "LTE",
        field: "state.retryState.timeoutAt",
        value: now,
      }),
    );
    expect(JSON.stringify(queries.unrecoverable.filter)).toContain(
      JSON.stringify(RetryConditions.unrecoverableCondition),
    );
    [queries.actionableNow, queries.timedOut, queries.unrecoverable].forEach(
      expectSnapshotWindow,
    );
    expect(queries).toHaveProperty("newerThanRange", {
      filter: {
        op: "AND",
        operands: [
          {
            op: "IN",
            field: "state.status",
            values: [
              ExecutionFailedStatus.FAILED,
              ExecutionFailedStatus.PREPARED,
            ],
          },
          {
            op: "GTE",
            field: "state.executeAt",
            value: snapshotWindow.end,
          },
        ],
      },
      metrics: [{ type: "COUNT", alias: "count" }],
    });
    expect(queries).toHaveProperty("activeTotal", {
      filter: {
        op: "IN",
        field: "state.status",
        values: [ExecutionFailedStatus.FAILED, ExecutionFailedStatus.PREPARED],
      },
      metrics: [{ type: "COUNT", alias: "count" }],
    });
    expect(queries).toHaveProperty("olderThanRange", {
      filter: {
        op: "AND",
        operands: [
          {
            op: "IN",
            field: "state.status",
            values: [
              ExecutionFailedStatus.FAILED,
              ExecutionFailedStatus.PREPARED,
            ],
          },
          {
            op: "LT",
            field: "state.executeAt",
            value: snapshotWindow.start,
          },
        ],
      },
      metrics: [{ type: "COUNT", alias: "count" }],
    });
    expect(queries.actionableNow.metrics).toEqual([
      { type: "COUNT", alias: "count" },
    ]);
    expect(queries.timedOut.metrics).toEqual([
      { type: "COUNT", alias: "count" },
    ]);
    expect(queries.unrecoverable.metrics).toEqual([
      { type: "COUNT", alias: "count" },
    ]);
  });

  it("applies one executeAt window to all seven Snapshot queries", () => {
    const keys = [
      {
        errorCode: "TEST_TIMEOUT",
        contextName: "billing",
        processorName: "OrderProcessor",
        functionName: "run",
        functionKind: "EVENT",
      },
    ];
    const summary = createSnapshotSummaryQueries(
      1_787_932_800_000,
      snapshotWindow,
    );
    const queries = [
      summary.actionableNow,
      summary.timedOut,
      summary.unrecoverable,
      createPressureQuery(snapshotWindow),
      createPressureStatusQuery(keys, snapshotWindow),
      createRecoverabilityQuery(snapshotWindow),
      createRetryHistogramQuery(snapshotWindow),
    ];

    expect(queries).toHaveLength(7);
    queries.forEach(expectSnapshotWindow);
    expect(JSON.stringify(queries[3].filter)).toContain('"state.status"');
    expect(JSON.stringify(queries[4].filter)).toContain('"TEST_TIMEOUT"');
    expect(JSON.stringify(queries[5].filter)).toContain('"state.status"');
    expect(JSON.stringify(queries[6].filter)).toContain('"state.status"');
  });

  it("groups pressure by error and the complete function identity", () => {
    expect(createPressureQuery(snapshotWindow)).toMatchObject({
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
      limit: 5,
    });
  });

  it("builds status counts only for the supplied pressure clusters", () => {
    const statusQuery = createPressureStatusQuery(
      [
        {
          errorCode: "TEST_TIMEOUT",
          contextName: "billing",
          processorName: "OrderProcessor",
          functionName: "run",
          functionKind: "EVENT",
        },
      ],
      snapshotWindow,
    );
    expect(JSON.stringify(statusQuery.filter)).toContain(
      JSON.stringify({
        op: "EQ",
        field: "state.error.errorCode",
        value: "TEST_TIMEOUT",
      }),
    );
    expect(statusQuery).toMatchObject({
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

    expect(
      createPressureStatusQuery([first, second], snapshotWindow).groupBy,
    ).toEqual([
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

  it("keeps five same-kind Top 5 clusters as distinct five-field predicates", () => {
    const keys = Array.from({ length: 5 }, (_, index) => ({
      errorCode: `ERROR_${index}`,
      contextName: `context_${index}`,
      processorName: `Processor_${index}`,
      functionName: `function_${index}`,
      functionKind: "EVENT",
    }));

    const query = createPressureStatusQuery(keys, snapshotWindow);
    expect(
      JSON.stringify(query.filter).match(/"field":"state\.error\.errorCode"/g),
    ).toHaveLength(5);
  });

  it("rejects a pressure status query without clusters", () => {
    expect(() => createPressureStatusQuery([], snapshotWindow)).toThrow(
      "Pressure status query requires at least one cluster.",
    );
  });

  it("groups active snapshots by recoverability", () => {
    const query = createRecoverabilityQuery(snapshotWindow);
    expect(JSON.stringify(query.filter)).toContain(
      JSON.stringify({
        op: "IN",
        field: "state.status",
        values: ["FAILED", "PREPARED"],
      }),
    );
    expect(query).toMatchObject({
      groupBy: [
        { type: "TERMS", field: "state.recoverable", alias: "recoverable" },
      ],
      metrics: [{ type: "COUNT", alias: "count" }],
    });
  });

  it("builds a bounded retry histogram for active snapshots", () => {
    const query = createRetryHistogramQuery(snapshotWindow);
    expect(JSON.stringify(query.filter)).toContain(
      JSON.stringify({
        op: "IN",
        field: "state.status",
        values: ["FAILED", "PREPARED"],
      }),
    );
    expect(query).toMatchObject({
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

  it("creates inclusive local calendar days with one daily bucket", () => {
    const window = createTrendWindow(
      new Date(2026, 7, 22, 15, 30),
      new Date(2026, 7, 28, 10, 37),
      "Asia/Shanghai",
    );

    expect(window).toEqual({
      buckets: Array.from({ length: 7 }, (_, index) =>
        new Date(2026, 7, 22 + index).getTime(),
      ),
      end: new Date(2026, 7, 29).getTime(),
      start: new Date(2026, 7, 22).getTime(),
      timeZone: "Asia/Shanghai",
      unit: AggregationDateUnit.DAY,
    });
  });

  it("allows exactly 1000 inclusive daily buckets", () => {
    const start = new Date(2023, 0, 1);
    const end = new Date(start);
    end.setDate(end.getDate() + 999);

    const window = createTrendWindow(start, end, "Asia/Shanghai");

    expect(window.buckets).toHaveLength(1_000);
    expect(createEventTrendQueries(window).succeeded.limit).toBe(1_000);
  });

  it("rejects 1001 inclusive daily buckets", () => {
    const start = new Date(2023, 0, 1);
    const end = new Date(start);
    end.setDate(end.getDate() + 1_000);

    expect(() => createTrendWindow(start, end, "Asia/Shanghai")).toThrow(
      "Date range cannot exceed 1000 days.",
    );
  });

  it("filters event streams by root time and body event name", () => {
    const window = createTrendWindow(
      new Date(2026, 7, 22),
      new Date(2026, 7, 28),
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
