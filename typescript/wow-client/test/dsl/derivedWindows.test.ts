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

/**
 * The window nodes of a derived expression (Kotlin's `DerivedExpression.Window`
 * and the `total` / `running` / `previous` / `rank` builders of
 * `DerivedExpressionDsl`), and what `aggregation.query()` checks of them.
 */

import { describe, expect, it } from 'vitest';
import {
  AGGREGATION_LIMITS,
  AggregationDateUnit,
  DerivedExpressionType,
  RunningFunction,
  SortDirection,
  aggregation,
  type AggregationMetric,
  type DerivedExpression,
} from '../../src/dsl.js';

const groupBy = [
  aggregation.terms('province', 'province'),
  aggregation.dateHistogram('createdAt', 'month', {
    unit: AggregationDateUnit.MONTH,
  }),
];
const gmv = aggregation.sum(aggregation.field('amount'), 'gmv');
const byMonth = { field: 'month', direction: SortDirection.ASC };

const query = (first: AggregationMetric, ...rest: AggregationMetric[]) =>
  aggregation.query({ groupBy, metrics: [first, ...rest] });

describe('derived windows', () => {
  it('builds each window with only the options given', () => {
    const windows = [
      aggregation.derived(d => d.total('gmv'), 'all'),
      aggregation.derived(d => d.total('gmv', 'province'), 'share'),
      aggregation.derived(d => d.running('gmv', byMonth), 'cumulative'),
      aggregation.derived(
        d =>
          d.running('gmv', byMonth, {
            within: ['province'],
            frame: 3,
            function: RunningFunction.AVG,
          }),
        'moving',
      ),
      aggregation.derived(
        d => d.previous('gmv', 'month', { period: AggregationDateUnit.YEAR }),
        'yoy',
      ),
      aggregation.derived(
        d =>
          d.rank('gmv', { within: ['province'], direction: SortDirection.ASC }),
        'rank',
      ),
    ];
    expect(
      windows.map(it => (it as { expression: DerivedExpression }).expression),
    ).toEqual([
      { type: DerivedExpressionType.TOTAL, metric: 'gmv' },
      {
        type: DerivedExpressionType.TOTAL,
        metric: 'gmv',
        within: ['province'],
      },
      { type: DerivedExpressionType.RUNNING, metric: 'gmv', order: byMonth },
      {
        type: DerivedExpressionType.RUNNING,
        metric: 'gmv',
        order: byMonth,
        within: ['province'],
        frame: 3,
        function: RunningFunction.AVG,
      },
      {
        type: DerivedExpressionType.PREVIOUS,
        metric: 'gmv',
        along: 'month',
        period: AggregationDateUnit.YEAR,
      },
      {
        type: DerivedExpressionType.RANK,
        metric: 'gmv',
        within: ['province'],
        direction: SortDirection.ASC,
      },
    ]);
    expect(query(gmv, ...windows).metrics).toHaveLength(7);
  });

  it('composes with arithmetic', () => {
    const share = aggregation.derived(
      d => d.divide(d.ref('gmv'), d.total('gmv', 'province')),
      'share',
    );
    expect(query(gmv, share).metrics).toHaveLength(2);
  });

  it('reads a non-derived numeric metric declared before it', () => {
    expect(() =>
      query(
        gmv,
        aggregation.derived(d => d.rank('nope'), 'rank'),
      ),
    ).toThrow(
      'RANK window of derived metric [rank] must read a metric declared before it, but was [nope].',
    );
    expect(() =>
      query(
        gmv,
        aggregation.derived(d => d.ref('gmv'), 'copy'),
        aggregation.derived(d => d.rank('copy'), 'rank'),
      ),
    ).toThrow(
      'RANK window of derived metric [rank] must read a non-derived numeric metric, but was [copy].',
    );
    expect(() =>
      query(
        aggregation.any('province', 'sample'),
        aggregation.derived(d => d.total('sample'), 'total'),
      ),
    ).toThrow('must read a non-derived numeric metric, but was [sample].');
  });

  it('computes at most MAX_WINDOWS windows', () => {
    const windows = Array.from(
      { length: AGGREGATION_LIMITS.MAX_WINDOWS },
      (_, index) => aggregation.derived(d => d.rank('gmv'), `r${index}`),
    );
    expect(query(gmv, ...windows).metrics).toHaveLength(9);
    expect(() =>
      query(
        gmv,
        ...windows,
        aggregation.derived(d => d.rank('gmv'), 'last'),
      ),
    ).toThrow(
      `an aggregation must compute at most ${AGGREGATION_LIMITS.MAX_WINDOWS} windows.`,
    );
  });
});
