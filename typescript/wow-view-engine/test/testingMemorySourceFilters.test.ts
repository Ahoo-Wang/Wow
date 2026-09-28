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

import { describe, expect, it } from 'vitest';
import {
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
  type AggregationMetric,
  type AggregationQuery,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../src/index.js';
import { matches, memorySource } from '../src/testing/index.js';

const HOUR = 3_600_000;

const field = (name: string) => ({
  type: AggregationExpressionType.FIELD as const,
  field: name,
});
const count = (alias = 'count'): AggregationMetric => ({
  type: AggregationMetricType.COUNT,
  alias,
});
function query(
  metrics: AggregationMetric[],
  rest: Omit<AggregationQuery, 'metrics'> = {},
): AggregationQuery {
  return { ...rest, metrics: metrics as AggregationQuery['metrics'] };
}

describe('memorySource: filters and readings', () => {
  describe('array and envelope filters', () => {
    const rows: RecordData[] = [
      {
        aggregateId: 'TO-1',
        ownerId: 'M1',
        state: { tags: ['GIFT', 'URGENT'] },
      },
      { aggregateId: 'TO-2', ownerId: 'M2', state: { tags: ['GIFT'] } },
      { aggregateId: 'TO-3', ownerId: 'M1', state: { tags: [] } },
    ];
    const source = memorySource(rows);
    const ids = async (filter: FilterExpression) =>
      (await source.paged({ filter })).list.map(row => row.aggregateId);

    it('holds an array field to every value with CONTAINS_ALL, as $all does', async () => {
      await expect(
        ids({
          op: FilterOperator.CONTAINS_ALL,
          field: 'state.tags',
          values: ['GIFT', 'URGENT'],
        } as FilterExpression),
      ).resolves.toEqual(['TO-1']);
    });

    // 「标记 没有条目」 failed with an English error here (second review
    // R1-P1-11). An array with no entries, as wow-mongo asks it ($size: 0).
    it('finds an array with no entries with IS_EMPTY, as $size: 0 does', async () => {
      await expect(
        ids({
          op: FilterOperator.IS_EMPTY,
          field: 'state.tags',
        } as FilterExpression),
      ).resolves.toEqual(['TO-3']);
    });

    it('reads the aggregate id and the owner off the snapshot envelope', async () => {
      await expect(
        ids({ op: FilterOperator.OWNER_ID, value: 'M1' } as FilterExpression),
      ).resolves.toEqual(['TO-1', 'TO-3']);
      await expect(
        ids({
          op: FilterOperator.AGGREGATE_ID,
          value: 'TO-2',
        } as FilterExpression),
      ).resolves.toEqual(['TO-2']);
      await expect(
        ids({
          op: FilterOperator.AGGREGATE_IDS,
          values: ['TO-1', 'TO-3'],
        } as FilterExpression),
      ).resolves.toEqual(['TO-1', 'TO-3']);
    });
  });

  describe('presence, as wow-mongo asks it ($exists)', () => {
    const rows: RecordData[] = [
      { id: 'a', state: { note: 'gift' } },
      { id: 'b', state: { note: null } },
      { id: 'c', state: {} },
    ];
    const source = memorySource(rows);
    const ids = async (op: FilterOperator) =>
      (
        await source.paged({
          filter: { op, field: 'state.note' } as FilterExpression,
        })
      ).list.map(row => row.id);

    it('counts a field holding null as existing, and one never written as not', async () => {
      await expect(ids(FilterOperator.EXISTS)).resolves.toEqual(['a', 'b']);
      await expect(ids(FilterOperator.NOT_EXISTS)).resolves.toEqual(['c']);
      await expect(ids(FilterOperator.IS_NULL)).resolves.toEqual(['b', 'c']);
    });

    // Wow lowers IS_NOT_EMPTY_STRING to `!= null AND != ""` (the semantics
    // matrix's `string.is-not-empty-string`): a field never written is not a
    // non-empty string. The story source read it as `!= ""` alone and
    // answered `unset` too.
    it('reads the empty-string questions as Wow lowers them', async () => {
      const texts = memorySource([
        { id: 'blank', remark: '' },
        { id: 'said', remark: 'fast' },
        { id: 'unset' },
      ]);
      const ask = async (op: FilterOperator) =>
        (
          await texts.paged({
            filter: { op, field: 'remark' } as FilterExpression,
          })
        ).list.map(row => row.id);
      await expect(ask(FilterOperator.IS_EMPTY_STRING)).resolves.toEqual([
        'blank',
      ]);
      await expect(ask(FilterOperator.IS_NOT_EMPTY_STRING)).resolves.toEqual([
        'said',
      ]);
    });
  });

  describe('the service clock (BEFORE_NOW, AFTER_NOW)', () => {
    const NOW = Date.parse('2026-09-22T02:00:00Z');
    const rows: RecordData[] = [
      { id: 'past', timeoutAt: NOW - HOUR, inSeconds: (NOW - HOUR) / 1000 },
      { id: 'now', timeoutAt: NOW, inSeconds: NOW / 1000 },
      { id: 'soon', timeoutAt: NOW + HOUR, inSeconds: (NOW + HOUR) / 1000 },
      { id: 'never' },
    ];
    const source = memorySource(rows, { now: () => NOW });
    const ids = async (
      op: FilterOperator.BEFORE_NOW | FilterOperator.AFTER_NOW,
      rest: Record<string, unknown> = {},
    ) =>
      (
        await source.paged({
          filter: {
            op,
            field: 'timeoutAt',
            offset: 'PT0S',
            timeUnit: 'MILLISECONDS',
            ...rest,
          } as FilterExpression,
        })
      ).list.map(row => row.id);

    it('compares strictly with its own now, and never matches a record without the time', async () => {
      await expect(ids(FilterOperator.BEFORE_NOW)).resolves.toEqual(['past']);
      await expect(ids(FilterOperator.AFTER_NOW)).resolves.toEqual(['soon']);
    });

    it('adds a signed ISO-8601 offset, as Duration.parse reads it', async () => {
      await expect(
        ids(FilterOperator.AFTER_NOW, { offset: '-PT30M' }),
      ).resolves.toEqual(['now', 'soon']);
      await expect(
        ids(FilterOperator.BEFORE_NOW, { offset: 'PT1H0.001S' }),
      ).resolves.toEqual(['past', 'now', 'soon']);
      await expect(
        ids(FilterOperator.BEFORE_NOW, { offset: '-P1D' }),
      ).resolves.toEqual([]);
    });

    it('encodes the moment in the field’s time unit', async () => {
      await expect(
        ids(FilterOperator.AFTER_NOW, {
          field: 'inSeconds',
          timeUnit: 'SECONDS',
        }),
      ).resolves.toEqual(['soon']);
    });

    it('lowers the clock in a metric’s own filter too', async () => {
      const [row] = await source.aggregate(
        query([
          {
            type: AggregationMetricType.COUNT,
            alias: 'overdue',
            filter: {
              op: FilterOperator.BEFORE_NOW,
              field: 'timeoutAt',
              offset: 'PT0S',
            } as FilterExpression,
          } as AggregationMetric,
        ]),
      );
      expect(row).toEqual({ overdue: 1 });
    });

    it('refuses a duration Duration.parse refuses', async () => {
      for (const offset of ['PT', 'P', '1H', 'PT1.5M'])
        await expect(ids(FilterOperator.AFTER_NOW, { offset })).rejects.toThrow(
          /does not read the duration/,
        );
    });
  });

  describe('refusals', () => {
    it('still refuses what it cannot translate', async () => {
      const source = memorySource([{ amount: 1 }]);
      await expect(
        source.aggregate(
          query([count()], {
            filter: {
              op: 'SPACE_ID',
              value: 'north',
            } as unknown as FilterExpression,
          }),
        ),
      ).rejects.toThrow(/does not evaluate SPACE_ID/);
      await expect(
        source.aggregate(
          query([count()], {
            groupBy: [
              {
                type: AggregationGroupType.DATE_HISTOGRAM,
                field: 'amount',
                unit: 'FORTNIGHT' as AggregationDateUnit,
                timeZone: 'UTC',
                alias: 'at',
              },
            ],
          }),
        ),
      ).rejects.toThrow(/does not bucket by FORTNIGHT/);
    });
  });

  describe('what the two sources it replaced each read their own way', () => {
    const orders: RecordData[] = [
      {
        aggregateId: 'A',
        createdAt: '2026-09-14T11:10:00.000Z',
        state: { lines: [{ sku: 'x', qty: 1 }], note: 'gift' },
      },
      {
        aggregateId: 'B',
        createdAt: '2026-09-16T01:05:00.000Z',
        state: { lines: [{ sku: 'y', qty: 3 }] },
      },
    ];

    // The story source answered MIN / MAX through `$max`, which compares any
    // value; the console's stub through numbers alone. MongoDB's is the first.
    it('answers the extreme of a time kept as text, as $min / $max do', async () => {
      const extreme = (fn: AggregationFunction) => ({
        type: AggregationMetricType.NUMERIC,
        function: fn,
        expression: field('createdAt'),
        alias: fn,
      });
      await expect(
        memorySource(orders).aggregate(
          query([
            extreme(AggregationFunction.MIN) as AggregationMetric,
            extreme(AggregationFunction.MAX) as AggregationMetric,
          ]),
        ),
      ).resolves.toEqual([
        { MIN: '2026-09-14T11:10:00.000Z', MAX: '2026-09-16T01:05:00.000Z' },
      ]);
    });

    it('reads an ELEMENT_MATCH predicate relative to the element', async () => {
      const ids = async (predicateField: string) =>
        (
          await memorySource(orders).paged({
            filter: {
              op: FilterOperator.ELEMENT_MATCH,
              field: 'state.lines',
              predicate: {
                op: FilterOperator.GT,
                field: predicateField,
                value: 2,
              },
            } as FilterExpression,
          })
        ).list.map(row => row.aggregateId);
      await expect(ids('qty')).resolves.toEqual(['B']);
      // The full path names nothing inside the element.
      await expect(ids('state.lines.qty')).resolves.toEqual([]);
    });

    it('answers only the projected paths, through arrays as MongoDB does', async () => {
      const { list } = await memorySource(orders).paged({
        filter: { op: FilterOperator.MATCH_ALL } as FilterExpression,
        projection: { include: ['aggregateId', 'state.lines.sku'] },
      });
      expect(list).toEqual([
        { aggregateId: 'A', state: { lines: [{ sku: 'x' }] } },
        { aggregateId: 'B', state: { lines: [{ sku: 'y' }] } },
      ]);
    });

    it('reads a date group in UTC when it names no zone, as Wow defaults it', async () => {
      const [row] = await memorySource([
        { at: Date.parse('2026-09-14T23:30:00Z') },
      ]).aggregate(
        query([count()], {
          groupBy: [
            {
              type: AggregationGroupType.DATE_HISTOGRAM,
              field: 'at',
              unit: AggregationDateUnit.DAY,
              alias: 'day',
            },
          ],
        }),
      );
      expect(row.day).toBe(Date.parse('2026-09-14T00:00:00Z'));
    });

    it('matches a condition of a test’s own, deleted documents included', () => {
      const clock = () => Date.parse('2026-09-15T00:00:00Z');
      const before = {
        op: FilterOperator.BEFORE_NOW,
        field: 'at',
        offset: 'PT0S',
      } as FilterExpression;
      expect(
        matches({ at: clock() - 1, deleted: true }, before, { now: clock }),
      ).toBe(true);
      expect(matches({ at: clock() }, before, { now: clock })).toBe(false);
    });
  });
});
