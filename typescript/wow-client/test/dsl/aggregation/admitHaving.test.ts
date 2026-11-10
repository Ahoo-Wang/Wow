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
  HavingExpressionType,
  ComparisonOperator,
  type HavingExpression,
  aggregation,
} from '../../../src/dsl.js';
import { count } from '../../support/aggregationQueries';

/**
 * Wow admits an `AggregationQuery` in its constructor and answers one that
 * fails with a 400. Each case here is one of those `require` calls, checked
 * against the object the client is about to send.
 */
describe('aggregation.query', () => {
  describe('having', () => {
    const grouped = [aggregation.terms('state.status', 'status')];

    it('refuses having with nothing grouped', () => {
      expect(() =>
        aggregation.query({
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.CONDITION,
            metric: 'rows',
            operator: ComparisonOperator.GT,
            value: 10,
          },
        }),
      ).toThrow('having requires at least one groupBy.');
    });

    it('refuses a metric it never declared', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.CONDITION,
            metric: 'ghost',
            operator: ComparisonOperator.GT,
            value: 10,
          },
        }),
      ).toThrow(
        'having condition [ghost] must reference a declared metric alias.',
      );
    });

    it('refuses an ANY metric, whose value is a sample', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [aggregation.any('state.status', 'sample')],
          having: {
            type: HavingExpressionType.IS_NULL,
            metric: 'sample',
          },
        }),
      ).toThrow('having condition [sample] cannot reference ANY metric.');
    });

    it('refuses a FIRST or LAST metric, whose value is a field value', () => {
      for (const edge of [
        aggregation.first('state.price', 'edge'),
        aggregation.last('state.price', 'edge'),
      ])
        expect(() =>
          aggregation.query({
            groupBy: grouped,
            metrics: [edge],
            having: { type: HavingExpressionType.IS_NULL, metric: 'edge' },
          }),
        ).toThrow(
          'having condition [edge] cannot reference FIRST or LAST metric.',
        );
    });

    it('refuses bounds the wrong way round', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.BETWEEN,
            metric: 'rows',
            lower: 10,
            upper: 1,
          },
        }),
      ).toThrow(
        'having between [rows] lower bound must not exceed upper bound.',
      );
    });

    it('refuses an empty IN', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.IN,
            metric: 'rows',
            values: [] as unknown as [number, ...number[]],
          },
        }),
      ).toThrow('having in [rows] values must not be empty.');
    });

    it('takes a having that names a real metric', () => {
      const query = aggregation.query({
        groupBy: grouped,
        metrics: [count('rows')],
        having: {
          type: HavingExpressionType.AND,
          operands: [
            {
              type: HavingExpressionType.CONDITION,
              metric: 'rows',
              operator: ComparisonOperator.GT,
              value: 10,
            },
            {
              type: HavingExpressionType.BETWEEN,
              metric: 'rows',
              lower: 1,
              upper: 9,
            },
            { type: HavingExpressionType.IN, metric: 'rows', values: [1, 2] },
            {
              type: HavingExpressionType.IS_NULL,
              metric: 'rows',
              negated: true,
            },
          ],
        },
      });

      expect(query.having?.type).toBe(HavingExpressionType.AND);
    });

    it.each([
      [
        'having condition [rows] value must be finite.',
        {
          type: HavingExpressionType.CONDITION,
          metric: 'rows',
          operator: ComparisonOperator.GT,
          value: Number.NaN,
        },
      ],
      [
        'having between [rows] bounds must be finite.',
        {
          type: HavingExpressionType.BETWEEN,
          metric: 'rows',
          lower: 1,
          upper: Number.POSITIVE_INFINITY,
        },
      ],
      [
        'having in [rows] values must be finite.',
        {
          type: HavingExpressionType.IN,
          metric: 'rows',
          values: [1, Number.NaN],
        },
      ],
      [
        'having AND operands must not be empty.',
        { type: HavingExpressionType.AND, operands: [] },
      ],
      [
        'having OR operands must not be empty.',
        { type: HavingExpressionType.OR, operands: [] },
      ],
    ])('refuses a having with %s', (message, having) => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: having as HavingExpression,
        }),
      ).toThrow(message);
    });

    it('refuses conditions nested past the depth Wow compiles', () => {
      const nest = (depth: number): HavingExpression =>
        depth <= 1
          ? { type: HavingExpressionType.IS_NULL, metric: 'rows' }
          : { type: HavingExpressionType.AND, operands: [nest(depth - 1)] };

      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: nest(9),
        }),
      ).toThrow('having expression depth must be at most 8.');
    });

    it('descends into AND and OR', () => {
      expect(() =>
        aggregation.query({
          groupBy: grouped,
          metrics: [count('rows')],
          having: {
            type: HavingExpressionType.AND,
            operands: [
              {
                type: HavingExpressionType.OR,
                operands: [
                  {
                    type: HavingExpressionType.CONDITION,
                    metric: 'ghost',
                    operator: ComparisonOperator.GT,
                    value: 1,
                  },
                ],
              },
            ],
          },
        }),
      ).toThrow(
        'having condition [ghost] must reference a declared metric alias.',
      );
    });
  });
});
