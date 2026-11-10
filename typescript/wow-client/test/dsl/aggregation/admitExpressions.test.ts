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
  DerivedExpressionType,
  type DerivedExpression,
  AggregationExpressionOperator,
  AggregationExpressionType,
  filter,
  aggregation,
  type AggregationExpression,
} from '../../../src/dsl.js';
import { count } from '../../fixtures/aggregationQueries';

/**
 * Wow admits an `AggregationQuery` in its constructor and answers one that
 * fails with a 400. Each case here is one of those `require` calls, checked
 * against the object the client is about to send.
 */
describe('aggregation.query', () => {
  const chain = (depth: number): AggregationExpression =>
    depth <= 1
      ? aggregation.constant(1)
      : aggregation.add(chain(depth - 1), aggregation.constant(1));
  const fullTree = (depth: number): AggregationExpression =>
    depth <= 1
      ? aggregation.constant(1)
      : aggregation.add(fullTree(depth - 1), fullTree(depth - 1));
  const derivedChain = (depth: number): DerivedExpression =>
    depth <= 1
      ? { type: DerivedExpressionType.CONSTANT, value: 1 }
      : {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: derivedChain(depth - 1),
          right: { type: DerivedExpressionType.CONSTANT, value: 1 },
        };
  const derivedFullTree = (depth: number): DerivedExpression =>
    depth <= 1
      ? { type: DerivedExpressionType.CONSTANT, value: 1 }
      : {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: derivedFullTree(depth - 1),
          right: derivedFullTree(depth - 1),
        };
  const metricRef = (metric: string): DerivedExpression => ({
    type: DerivedExpressionType.METRIC_REF,
    metric,
  });

  describe('expressions', () => {
    it('refuses arithmetic nested past the depth Wow compiles', () => {
      expect(() =>
        aggregation.query({
          metrics: [aggregation.sum(chain(9), 'total')],
        }),
      ).toThrow('aggregation expression depth must be at most 8.');
    });

    it('takes arithmetic at the depth Wow compiles', () => {
      expect(
        aggregation.query({ metrics: [aggregation.sum(chain(8), 'total')] })
          .metrics,
      ).toHaveLength(1);
    });

    it.each([Number.NaN, Number.POSITIVE_INFINITY])(
      'refuses the constant %s, which JSON cannot carry',
      value => {
        // It would serialise to null and be refused on arrival instead.
        expect(() =>
          aggregation.query({
            metrics: [
              aggregation.sum(
                {
                  type: AggregationExpressionType.CONSTANT,
                  value,
                } as AggregationExpression,
                'total',
              ),
            ],
          }),
        ).toThrow('aggregation constant must be finite.');
      },
    );

    it('spends one node budget across every metric', () => {
      // 255 nodes each: neither metric is too large on its own.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.sum(fullTree(8), 'a'),
            aggregation.sum(fullTree(8), 'b'),
          ],
        }),
      ).toThrow('aggregation expressions must contain at most 256 nodes.');
    });
  });

  describe('derived metrics', () => {
    it('refuses a reference to a metric declared after it', () => {
      // Derived arithmetic runs over values the query already produced, so
      // the list has to be evaluable in one pass.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.derived(metricRef('total'), 'share'),
            aggregation.sum(aggregation.field('amount'), 'total'),
          ],
        }),
      ).toThrow(
        'derived metric [share] must reference a metric declared before it, but was [total].',
      );
    });

    it('refuses a reference to an ANY metric', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.any('state.status', 'sample'),
            aggregation.derived(metricRef('sample'), 'share'),
          ],
        }),
      ).toThrow('derived metric [share] cannot reference ANY metric [sample].');
    });

    it('refuses a reference to a FIRST or LAST metric', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.first('state.price', 'open'),
            aggregation.derived(metricRef('open'), 'share'),
          ],
        }),
      ).toThrow('derived metric [share] cannot reference FIRST metric [open].');
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.last('state.price', 'close'),
            aggregation.derived(metricRef('close'), 'share'),
          ],
        }),
      ).toThrow('derived metric [share] cannot reference LAST metric [close].');
    });

    it('takes a reference to a metric declared before it', () => {
      expect(
        aggregation.query({
          metrics: [
            aggregation.sum(aggregation.field('amount'), 'total'),
            aggregation.derived(metricRef('total'), 'share'),
          ],
        }).metrics,
      ).toHaveLength(2);
    });

    it('refuses arithmetic nested past the depth Wow compiles', () => {
      expect(() =>
        aggregation.query({
          metrics: [aggregation.derived(derivedChain(9), 'share')],
        }),
      ).toThrow('derived expression depth must be at most 8.');
    });

    it.each([Number.NaN, Number.NEGATIVE_INFINITY])(
      'refuses the constant %s, which JSON cannot carry',
      value => {
        expect(() =>
          aggregation.query({
            metrics: [
              aggregation.derived(
                { type: DerivedExpressionType.CONSTANT, value },
                'share',
              ),
            ],
          }),
        ).toThrow('derived constant must be finite.');
      },
    );

    it('refuses a metric that references itself', () => {
      // Its own alias is declared only once it has been admitted, so the
      // cycle cannot be written.
      expect(() =>
        aggregation.query({
          metrics: [aggregation.derived(metricRef('share'), 'share')],
        }),
      ).toThrow(
        'derived metric [share] must reference a metric declared before it, but was [share].',
      );
    });

    it('spends one node budget across every derived metric', () => {
      // 255 nodes each: neither is too large on its own.
      expect(() =>
        aggregation.query({
          metrics: [
            aggregation.derived(derivedFullTree(8), 'a'),
            aggregation.derived(derivedFullTree(8), 'b'),
          ],
        }),
      ).toThrow('derived expressions must contain at most 256 nodes.');
    });
  });

  describe('metric filters', () => {
    // A metric filter is a whole-value predicate on one record, so element
    // matching and full text have no reading there. Both backends refuse them
    // before looking at a schema.
    it('refuses a search', () => {
      expect(() =>
        aggregation.query({
          metrics: [count('rows', filter.search('premium'))],
        }),
      ).toThrow('Aggregation metric filters do not support search filters.');
    });

    it('refuses an element match', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            count(
              'rows',
              filter.elementMatch('items', filter.eq('status', 'OK')),
            ),
          ],
        }),
      ).toThrow('Aggregation metric filters do not support [ELEMENT_MATCH].');
    });

    it('descends into a logical filter', () => {
      expect(() =>
        aggregation.query({
          metrics: [
            count(
              'rows',
              filter.and([
                filter.eq('state.status', 'PAID'),
                filter.or([filter.search('premium')]),
              ]),
            ),
          ],
        }),
      ).toThrow('Aggregation metric filters do not support search filters.');
    });

    it('takes the metadata filters, which name the record it already has', () => {
      expect(
        aggregation.query({
          metrics: [count('rows', filter.ownerId('u-1'))],
        }).metrics,
      ).toHaveLength(1);
    });
  });
});
