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

import {
  aggregation,
  AggregationExpressionOperator,
  AggregationGroupScope,
  asc,
  filter,
  DerivedExpressionType,
  SortDirection,
  type DerivedExpression,
  type ElementFilterExpression,
  type HavingExpression,
} from '../../../src/dsl';
import { anyMetrics, type ConformanceRule } from './register';

const count = (alias: string) => aggregation.count(alias);
const grouped = [aggregation.terms('status', 'g')];

/** Wow's rules for an aggregation query. */
export const aggregationRules: ConformanceRule[] = [
  // ---- AggregationQuery -------------------------------------------------
  {
    wow: 'aggregation alias must contain one segment.',
    source: 'wow-api AggregationQuery.kt requireAggregationAlias',
    violate: () => aggregation.count('a.b'),
  },
  {
    wow: 'aggregation alias must not use the reserved __wow prefix.',
    source: 'wow-api AggregationQuery.kt requireAggregationAlias',
    violate: () => aggregation.count('__wowTotal'),
  },
  {
    wow: 'aggregation constant must be finite.',
    source: 'wow-api AggregationQuery.kt AggregationExpression.Constant.init',
    violate: () => aggregation.constant(Number.NaN),
  },
  {
    wow: 'derived constant must be finite.',
    source: 'wow-api AggregationQuery.kt DerivedExpression.Constant.init',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.derived(
            { type: 'constant', value: Number.NaN } as DerivedExpression,
            'd',
          ),
        ),
      }),
  },
  {
    wow: 'percentile must be finite and within (0, 100).',
    source: 'wow-api AggregationQuery.kt AggregationMetric.Percentile.init',
    violate: () =>
      aggregation.percentile(aggregation.constant(1), 'p', { percentile: 100 }),
  },
  {
    wow: 'histogram interval must be finite and greater than 0.',
    source: 'wow-api AggregationQuery.kt AggregationGroup.Histogram.init',
    violate: () => aggregation.histogram('a', 'h', { interval: 0 }),
  },
  {
    wow: '$type group requires exactly one of field and expression.',
    source: 'wow-api AggregationQuery.kt requireGroupInput',
    violate: () =>
      aggregation.query({
        groupBy: [{ type: 'histogram', alias: 'g', interval: 1 } as never],
        metrics: anyMetrics(count('m')),
      }),
    throws: 'HISTOGRAM group requires exactly one of field and expression.',
  },
  {
    wow: 'terms missingKey requires a field input.',
    source: 'wow-api AggregationQuery.kt AggregationGroup.Terms.init',
    violate: () =>
      aggregation.terms(aggregation.field('a'), 'g', { missingKey: 'NONE' }),
  },
  {
    wow: '$type with a ROOT scope requires a field input.',
    source: 'wow-api AggregationQuery.kt requireGroupScope',
    violate: () =>
      aggregation.terms(aggregation.field('a'), 'g', {
        scope: AggregationGroupScope.ROOT,
      }),
    throws: 'TERMS with a ROOT scope requires a field input.',
  },
  {
    wow: 'groups with a ROOT scope must sort before the element groups.',
    source:
      'wow-api AggregationQuery.kt AggregationQuery.requireRootGroupsFirst',
    violate: () =>
      aggregation.query({
        elements: [aggregation.element('items')],
        groupBy: [
          aggregation.terms('sku', 'sku'),
          aggregation.terms('id', 'id', { scope: AggregationGroupScope.ROOT }),
        ],
        metrics: anyMetrics(count('m')),
      }),
  },
  {
    wow: 'EXPRESSION value must be finite.',
    source: 'wow-api ExpressionFilter.kt ExpressionFilter.init',
    violate: () =>
      filter.expression(aggregation.field('a'), 'GT' as never, Number.NaN),
  },
  {
    wow: 'EXPRESSION must read at least one field.',
    source: 'wow-api ExpressionFilter.kt ExpressionFilter.init',
    violate: () => filter.expression(aggregation.constant(1), 'gt' as never, 1),
  },
  {
    wow: 'terms missingKey must not be blank.',
    source: 'wow-api AggregationQuery.kt AggregationGroup.Terms.init',
    violate: () => aggregation.terms('a', 'g', { missingKey: '  ' }),
  },
  {
    wow: 'Aggregation element filter cannot contain root filters.',
    source: 'wow-api AggregationQuery.kt AggregationElement.init',
    violate: () =>
      aggregation.element(
        'items',
        filter.ownerId('u-1') as unknown as ElementFilterExpression,
      ),
  },
  {
    wow: 'elements must contain at most $MAX_ELEMENTS paths.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        elements: Array.from({ length: 6 }, (_, i) =>
          aggregation.element(`e${i}`),
        ),
        metrics: anyMetrics(count('m')),
      }),
    throws: 'elements must contain at most 5 paths.',
  },
  {
    wow: 'groupBy must contain at most $MAX_GROUPS dimensions.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: Array.from({ length: 33 }, (_, i) =>
          aggregation.terms(`f${i}`, `g${i}`),
        ),
        metrics: anyMetrics(count('m')),
      }),
    throws: 'groupBy must contain at most 32 dimensions.',
  },
  {
    wow: 'metrics must not be empty.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () => aggregation.query({ metrics: anyMetrics() }),
  },
  {
    wow: 'metrics must contain at most $MAX_METRICS entries.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          ...Array.from({ length: 65 }, (_, i) => count(`m${i}`)),
        ),
      }),
    throws: 'metrics must contain at most 64 entries.',
  },
  {
    wow: 'sort must contain at most $MAX_SORT_FIELDS fields.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        sort: Array.from({ length: 33 }, (_, i) => asc(`s${i}`)),
      }),
    throws: 'sort must contain at most 32 fields.',
  },
  {
    wow: 'limit must be between 1 and $MAX_LIMIT.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({ metrics: anyMetrics(count('m')), limit: 0 }),
    throws: 'limit must be between 1 and 10000.',
  },
  {
    wow: 'sort requires at least one groupBy.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({ metrics: anyMetrics(count('m')), sort: [asc('m')] }),
  },
  {
    wow: 'aggregation aliases must be unique.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: [aggregation.terms('a', 'dup')],
        metrics: anyMetrics(count('dup')),
      }),
  },
  {
    wow: 'sort fields must be unique.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        sort: [asc('m'), { field: 'm', direction: SortDirection.DESC }],
      }),
  },
  {
    wow: 'sort fields must reference aggregation aliases.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        sort: [asc('nope')],
      }),
  },
  {
    wow: 'effective sort must contain at most $MAX_SORT_FIELDS fields.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: Array.from({ length: 32 }, (_, i) =>
          aggregation.terms(`f${i}`, `g${i}`),
        ),
        metrics: anyMetrics(count('m')),
        sort: [asc('m')],
      }),
    throws: 'effective sort must contain at most 32 fields.',
  },
  {
    wow: 'dense requires DATE_HISTOGRAM to be the only groupBy.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: [
          aggregation.dateHistogram('t', 'd', {
            unit: 'day' as never,
            dense: true,
          }),
          aggregation.terms('a', 'g'),
        ],
        metrics: anyMetrics(count('m')),
      }),
  },
  {
    wow: 'dense requires DATE_PART to be the only groupBy.',
    source: 'wow-api AggregationQuery.kt AggregationQuery.init',
    violate: () =>
      aggregation.query({
        groupBy: [
          aggregation.datePart('t', 'd', {
            part: 'day-of-week' as never,
            dense: true,
          }),
          aggregation.terms('a', 'g'),
        ],
        metrics: anyMetrics(count('m')),
      }),
  },
  {
    wow: 'aggregation expression depth must be at most $MAX_EXPRESSION_DEPTH.',
    source: 'wow-api AggregationQuery.kt requireValidExpressions',
    violate: () => {
      const chain = (d: number): ReturnType<typeof aggregation.constant> =>
        d <= 1
          ? aggregation.constant(1)
          : (aggregation.add(chain(d - 1), aggregation.constant(1)) as never);
      return aggregation.query({
        metrics: anyMetrics(aggregation.sum(chain(9), 'm')),
      });
    },
    throws: 'aggregation expression depth must be at most 8.',
  },
  {
    wow: 'aggregation expressions must contain at most $MAX_EXPRESSION_NODES nodes.',
    source: 'wow-api AggregationQuery.kt requireValidExpressions',
    violate: () => {
      const tree = (d: number): ReturnType<typeof aggregation.constant> =>
        d <= 1
          ? aggregation.constant(1)
          : (aggregation.add(tree(d - 1), tree(d - 1)) as never);
      return aggregation.query({
        metrics: anyMetrics(
          aggregation.sum(tree(8), 'a'),
          aggregation.sum(tree(8), 'b'),
        ),
      });
    },
    throws: 'aggregation expressions must contain at most 256 nodes.',
  },
  {
    wow: 'derived metric [$alias] must reference a metric declared before it, but was [$reference].',
    source: 'wow-api AggregationQuery.kt requireValidDerivedExpression',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.derived(
            { type: 'metric-ref', metric: 'later' } as DerivedExpression,
            'd',
          ),
          count('later'),
        ),
      }),
    throws:
      'derived metric [d] must reference a metric declared before it, but was [later].',
  },
  {
    wow: 'derived metric [$alias] cannot reference $nonNumeric metric [$reference].',
    source: 'wow-api AggregationQuery.kt requireValidDerivedExpression',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.any('a', 'sample'),
          aggregation.derived(
            { type: 'metric-ref', metric: 'sample' } as DerivedExpression,
            'd',
          ),
        ),
      }),
    throws: 'derived metric [d] cannot reference ANY metric [sample].',
  },
  {
    wow: 'derived expression depth must be at most $MAX_EXPRESSION_DEPTH.',
    source: 'wow-api AggregationQuery.kt requireValidDerivedExpression',
    violate: () => {
      const chain = (d: number): DerivedExpression =>
        d <= 1
          ? { type: DerivedExpressionType.CONSTANT, value: 1 }
          : {
              type: DerivedExpressionType.BINARY,
              operator: AggregationExpressionOperator.ADD,
              left: chain(d - 1),
              right: { type: DerivedExpressionType.CONSTANT, value: 1 },
            };
      return aggregation.query({
        metrics: anyMetrics(aggregation.derived(chain(9), 'd')),
      });
    },
    throws: 'derived expression depth must be at most 8.',
  },
  {
    wow: 'derived expressions must contain at most $MAX_EXPRESSION_NODES nodes.',
    source: 'wow-api AggregationQuery.kt requireValidDerivedExpression',
    violate: () => {
      const tree = (d: number): DerivedExpression =>
        d <= 1
          ? { type: DerivedExpressionType.CONSTANT, value: 1 }
          : {
              type: DerivedExpressionType.BINARY,
              operator: AggregationExpressionOperator.ADD,
              left: tree(d - 1),
              right: tree(d - 1),
            };
      return aggregation.query({
        metrics: anyMetrics(
          aggregation.derived(tree(8), 'a'),
          aggregation.derived(tree(8), 'b'),
        ),
      });
    },
    throws: 'derived expressions must contain at most 256 nodes.',
  },
  {
    wow: 'having requires at least one groupBy.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(count('m')),
        having: { type: 'is-null', metric: 'm' } as HavingExpression,
      }),
  },
  {
    wow: 'having condition [$metric] must reference a declared metric alias.',
    source: 'wow-api AggregationQuery.kt requireValidHavingMetric',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: { type: 'is-null', metric: 'nope' } as HavingExpression,
      }),
    throws: 'having condition [nope] must reference a declared metric alias.',
  },
  {
    wow: 'having condition [$metric] cannot reference ANY metric.',
    source: 'wow-api AggregationQuery.kt requireValidHavingMetric',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(aggregation.any('a', 'sample')),
        having: { type: 'is-null', metric: 'sample' } as HavingExpression,
      }),
    throws: 'having condition [sample] cannot reference ANY metric.',
  },
  {
    wow: 'having condition [$metric] cannot reference FIRST or LAST metric.',
    source: 'wow-api AggregationQuery.kt requireValidHavingMetric',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(aggregation.last('a', 'close')),
        having: { type: 'is-null', metric: 'close' } as HavingExpression,
      }),
    throws: 'having condition [close] cannot reference FIRST or LAST metric.',
  },
  {
    wow: 'having condition [${current.metric}] value must be finite.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'condition',
          metric: 'm',
          operator: 'gt',
          value: Number.NaN,
        } as HavingExpression,
      }),
    throws: 'having condition [m] value must be finite.',
  },
  {
    wow: 'having between [${current.metric}] bounds must be finite.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'between',
          metric: 'm',
          lower: 1,
          upper: Number.NaN,
        } as HavingExpression,
      }),
    throws: 'having between [m] bounds must be finite.',
  },
  {
    wow: 'having between [${current.metric}] lower bound must not exceed upper bound.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'between',
          metric: 'm',
          lower: 9,
          upper: 1,
        } as HavingExpression,
      }),
    throws: 'having between [m] lower bound must not exceed upper bound.',
  },
  {
    wow: 'having in [${current.metric}] values must not be empty.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'in',
          metric: 'm',
          values: [],
        } as unknown as HavingExpression,
      }),
    throws: 'having in [m] values must not be empty.',
  },
  {
    wow: 'having in [${current.metric}] values must be finite.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'in',
          metric: 'm',
          values: [Number.NaN],
        } as HavingExpression,
      }),
    throws: 'having in [m] values must be finite.',
  },
  {
    wow: 'having AND operands must not be empty.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'and',
          operands: [],
        } as unknown as HavingExpression,
      }),
  },
  {
    wow: 'having OR operands must not be empty.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'or',
          operands: [],
        } as unknown as HavingExpression,
      }),
  },
  {
    wow: 'having expression depth must be at most $MAX_EXPRESSION_DEPTH.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () => {
      const nest = (d: number): HavingExpression =>
        d <= 1
          ? ({ type: 'is-null', metric: 'm' } as HavingExpression)
          : ({ type: 'and', operands: [nest(d - 1)] } as HavingExpression);
      return aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: nest(9),
      });
    },
    throws: 'having expression depth must be at most 8.',
  },
];
