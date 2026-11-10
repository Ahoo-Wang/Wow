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

import { effectiveSort } from '../../../src/dsl/aggregation/sort';
import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  type DerivedExpression,
  type HavingExpression,
  filter,
  SortDirection,
  aggregation,
  AGGREGATION_LIMITS,
  type AggregationExpression,
  type AggregationQuery,
} from '../../../src/dsl.js';
import { count } from '../../support/aggregationQueries';

/**
 * Wow admits an `AggregationQuery` in its constructor and answers one that
 * fails with a 400. Each case here is one of those `require` calls, checked
 * against the object the client is about to send.
 */
describe('aggregation.query', () => {
  it('returns a query that breaks no rule', () => {
    const query = aggregation.query({
      filter: filter.eq('state.status', 'PAID'),
      groupBy: [aggregation.terms('state.status', 'status')],
      metrics: [
        count('rows'),
        aggregation.sum(aggregation.field('amount'), 'total'),
      ],
      sort: [{ field: 'total', direction: SortDirection.DESC }],
      limit: 50,
    });

    expect(query.metrics).toHaveLength(2);
    expect(query.limit).toBe(50);
  });

  it('publishes the sizes it enforces', () => {
    expect(AGGREGATION_LIMITS).toEqual({
      DEFAULT_LIMIT: 100,
      MAX_LIMIT: 10_000,
      MAX_ELEMENTS: 5,
      MAX_GROUPS: 32,
      MAX_METRICS: 64,
      MAX_SORT_FIELDS: 32,
      MAX_EXPRESSION_DEPTH: 8,
      MAX_EXPRESSION_NODES: 256,
      MAX_WINDOWS: 8,
    });
  });

  it.each([
    [
      'metrics must not be empty.',
      { metrics: [] as unknown as AggregationQuery['metrics'] },
    ],
    [
      'metrics must contain at most 64 entries.',
      {
        metrics: Array.from({ length: 65 }, (_, index) =>
          count(`m${index}`),
        ) as unknown as AggregationQuery['metrics'],
      },
    ],
    [
      'elements must contain at most 5 paths.',
      {
        elements: Array.from({ length: 6 }, (_, index) =>
          aggregation.element(`items${index}`),
        ),
        metrics: [count('rows')] as AggregationQuery['metrics'],
      },
    ],
    [
      'groupBy must contain at most 32 dimensions.',
      {
        groupBy: Array.from({ length: 33 }, (_, index) =>
          aggregation.terms(`f${index}`, `g${index}`),
        ),
        metrics: [count('rows')] as AggregationQuery['metrics'],
      },
    ],
  ])('refuses a query with %s', (message, query) => {
    expect(() => aggregation.query(query as AggregationQuery)).toThrow(message);
  });

  it.each([0, -1, 10_001, 1.5])('refuses the limit %s', limit => {
    expect(() =>
      aggregation.query({ metrics: [count('rows')], limit }),
    ).toThrow('limit must be between 1 and 10000.');
  });

  it('takes a limit at either end of the range', () => {
    expect(
      aggregation.query({ metrics: [count('rows')], limit: 1 }).limit,
    ).toBe(1);
    expect(
      aggregation.query({ metrics: [count('rows')], limit: 10_000 }).limit,
    ).toBe(10_000);
  });

  it('refuses two aliases that collide', () => {
    expect(() =>
      aggregation.query({
        groupBy: [aggregation.terms('state.status', 'total')],
        metrics: [count('total')],
      }),
    ).toThrow('aggregation aliases must be unique.');
  });

  describe('sort', () => {
    it('refuses a sort with nothing grouped to sort', () => {
      // One row comes back, so there is no order to choose.
      expect(() =>
        aggregation.query({
          metrics: [count('rows')],
          sort: [{ field: 'rows', direction: SortDirection.DESC }],
        }),
      ).toThrow('sort requires at least one groupBy.');
    });

    it('refuses the same field twice', () => {
      expect(() =>
        aggregation.query({
          groupBy: [aggregation.terms('state.status', 'status')],
          metrics: [count('rows')],
          sort: [
            { field: 'rows', direction: SortDirection.DESC },
            { field: 'rows', direction: SortDirection.ASC },
          ],
        }),
      ).toThrow('sort fields must be unique.');
    });

    it('refuses a field that is not an alias', () => {
      // A result row holds aliases, not the fields they came from.
      expect(() =>
        aggregation.query({
          groupBy: [aggregation.terms('state.status', 'status')],
          metrics: [count('rows')],
          sort: [{ field: 'state.status', direction: SortDirection.ASC }],
        }),
      ).toThrow('sort fields must reference aggregation aliases.');
    });

    it('counts the groups it appends when checking the ceiling', () => {
      expect(() =>
        aggregation.query({
          groupBy: Array.from({ length: 32 }, (_, index) =>
            aggregation.terms(`f${index}`, `g${index}`),
          ),
          metrics: [count('rows')],
          sort: [{ field: 'rows', direction: SortDirection.DESC }],
        }),
      ).toThrow('effective sort must contain at most 32 fields.');
    });

    it('has nothing to apply to an ungrouped query', () => {
      expect(effectiveSort({})).toEqual([]);
    });

    it('appends each unsorted group ascending', () => {
      expect(
        effectiveSort({
          groupBy: [
            aggregation.terms('state.status', 'status'),
            aggregation.terms('state.region', 'region'),
          ],
          sort: [{ field: 'region', direction: SortDirection.DESC }],
        }),
      ).toEqual([
        { field: 'region', direction: SortDirection.DESC },
        { field: 'status', direction: SortDirection.ASC },
      ]);
    });
  });

  it('refuses a dense date histogram beside another dimension', () => {
    // Dense fills in the buckets its range implies rather than only the ones
    // with rows, and a second dimension would multiply that filling out.
    expect(() =>
      aggregation.query({
        groupBy: [
          aggregation.dateHistogram('createdAt', 'day', {
            unit: AggregationDateUnit.DAY,
            dense: true,
          }),
          aggregation.terms('state.status', 'status'),
        ],
        metrics: [count('rows')],
      }),
    ).toThrow('dense requires DATE_HISTOGRAM to be the only groupBy.');
  });

  it('refuses a dense date part beside another dimension', () => {
    expect(() =>
      aggregation.query({
        groupBy: [
          aggregation.datePart('createdAt', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
            dense: true,
          }),
          aggregation.terms('state.status', 'status'),
        ],
        metrics: [count('rows')],
      }),
    ).toThrow('dense requires DATE_PART to be the only groupBy.');
    expect(
      aggregation.query({
        groupBy: [
          aggregation.datePart('createdAt', 'weekday', {
            part: AggregationDatePart.DAY_OF_WEEK,
          }),
          aggregation.datePart('createdAt', 'hour', {
            part: AggregationDatePart.HOUR_OF_DAY,
          }),
        ],
        metrics: [count('rows')],
      }).groupBy,
    ).toHaveLength(2);
  });

  // A query may be rebuilt from a stored config, so a node can carry a `type`
  // this version does not know. That is a finding, not a crash.
  it.each([
    [
      'Unsupported aggregation expression: WINDOW.',
      {
        metrics: [
          aggregation.sum(
            { type: 'WINDOW' } as unknown as AggregationExpression,
            'total',
          ),
        ] as AggregationQuery['metrics'],
      },
    ],
    [
      'Unsupported derived expression: RATIO.',
      {
        metrics: [
          aggregation.derived(
            { type: 'RATIO' } as unknown as DerivedExpression,
            'share',
          ),
        ] as AggregationQuery['metrics'],
      },
    ],
    [
      'Unsupported having expression: REGEX.',
      {
        groupBy: [aggregation.terms('state.status', 'status')],
        metrics: [aggregation.count('rows')] as AggregationQuery['metrics'],
        having: { type: 'REGEX' } as unknown as HavingExpression,
      },
    ],
  ])('refuses a node it cannot read: %s', (message, query) => {
    expect(() => aggregation.query(query as AggregationQuery)).toThrow(message);
  });

  it('names the aggregation element when its filter reaches past an element', () => {
    // Not "ELEMENT_MATCH predicate": nobody wrote an ELEMENT_MATCH here.
    expect(() =>
      aggregation.element('items', filter.ownerId('u-1') as never),
    ).toThrow('Aggregation element filter cannot contain root filters.');
  });

  it('refuses any SEARCH in an aggregation element filter, even one naming fields', () => {
    expect(() =>
      aggregation.element(
        'items',
        filter.search('usb', { fields: ['productName'] }),
      ),
    ).toThrow('Aggregation element filter cannot contain root filters.');
  });
});
