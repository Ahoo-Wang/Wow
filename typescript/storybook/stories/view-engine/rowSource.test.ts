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

// What the stories' source adds to the package's in-memory source. Wow's
// semantics themselves — filters, paging, aggregation — are held by
// wow-view-engine's own suites (`test/testing*.test.ts`), against the
// server's semantics matrix and its TCK.

import { describe, expect, it } from 'vitest';
import {
  AggregationExpressionType,
  AggregationFunction,
  AggregationMetricType,
  FilterOperator,
  type AggregationQuery,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '@ahoo-wang/wow-view-engine';
import { rowSource } from './rowSource.js';

const totals: AggregationQuery = {
  metrics: [
    {
      type: AggregationMetricType.NUMERIC,
      function: AggregationFunction.SUM,
      expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
      alias: 'amount',
    },
  ],
};

describe('rowSource', () => {
  it('answers a repeated aggregation from memory, each time with its own copy', async () => {
    const rows: RecordData[] = [{ amount: 1 }, { amount: 2 }];
    const source = rowSource(rows);
    const first = await source.aggregate(totals);
    expect(first).toEqual([{ amount: 3 }]);
    first[0].amount = 1_000;
    // A story's rows are fixed for its source's life; changing one under it
    // shows which answers come from memory.
    rows[0].amount = 100;
    expect(await source.aggregate(totals)).toEqual([{ amount: 3 }]);
    expect(await source.aggregate({ ...totals, limit: 10 })).toEqual([
      { amount: 102 },
    ]);
  });

  it('keeps the time column in order and reads its own clock', async () => {
    const now = Date.parse('2026-09-22T02:00:00Z');
    const source = rowSource(
      [
        { id: 'later', at: now + 1 },
        { id: 'earlier', at: now - 1 },
      ],
      { timeField: 'at', now: () => now },
    );
    const { list } = await source.paged({
      filter: {
        op: FilterOperator.BEFORE_NOW,
        field: 'at',
        offset: 'PT1S',
      } as FilterExpression,
    });
    expect(list.map(row => row.id)).toEqual(['earlier', 'later']);
  });
});
