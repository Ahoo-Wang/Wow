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
  aggregation,
  asc,
  cursorQuery,
  filter,
  projection,
  DeletionState,
  SortDirection,
  type AggregationQuery,
  type DerivedExpression,
  type ElementFilterExpression,
  type FilterExpression,
  type HavingExpression,
} from '../../src';

/**
 * The register of Wow's own query rules.
 *
 * Wow states each of these by throwing. This package speaks the same protocol,
 * so every one of them is either mirrored here, satisfied by the shape of the
 * builders, or left to the server — and which of the three, for each rule, is
 * written down rather than inferred.
 *
 * `wow` is the message Wow throws, verbatim, and is the key: search Wow's
 * source for it to find the rule, and search this file for a message Wow
 * throws to see whether it is accounted for.
 *
 * `throws` is what this package says, which is not always the same wording.
 * Recording both keeps the divergence visible: `filter.gt('a', null)` is
 * refused for being null, but the message it gives is the scalar one.
 */
interface ConformanceRule {
  /** The message Wow throws, verbatim. */
  wow: string;
  /** Where Wow states it. */
  source: string;
  /** An input that breaks the rule. */
  violate?: () => unknown;
  /** What this package throws for that input, when it differs from `wow`. */
  throws?: string;
  /** Why no input can break it here. */
  byConstruction?: string;
  /** Why this package does not mirror it. */
  serverOnly?: string;
}

const anyMetrics = (...metrics: unknown[]) =>
  metrics as unknown as AggregationQuery['metrics'];
const count = (alias: string) => aggregation.count(alias);
const grouped = [aggregation.terms('status', 'g')];

const RULES: ConformanceRule[] = [
  // ---- QueryField -------------------------------------------------------
  {
    wow: 'Query field is invalid: [$path].',
    source: 'wow-api FilterExpression.kt QueryField.init',
    violate: () => filter.eq('9 not a path', 1),
    throws: 'Query field is invalid: [9 not a path].',
  },

  // ---- FilterExpression -------------------------------------------------
  {
    wow: 'AND operands cannot be empty.',
    source: 'wow-api FilterExpression.kt AndFilter.init',
    violate: () => filter.and([]),
  },
  {
    wow: 'OR operands cannot be empty.',
    source: 'wow-api FilterExpression.kt OrFilter.init',
    violate: () => filter.or([]),
  },
  {
    wow: 'NOR operands cannot be empty.',
    source: 'wow-api FilterExpression.kt NorFilter.init',
    violate: () => filter.nor([]),
  },
  {
    wow: 'ELEMENT_MATCH predicate cannot contain root filters.',
    source: 'wow-api FilterExpression.kt ElementMatchFilter.init',
    violate: () =>
      filter.elementMatch(
        'items',
        filter.ownerId('u-1') as unknown as ElementFilterExpression,
      ),
  },
  {
    wow: 'SEARCH query cannot be blank.',
    source: 'wow-api FilterExpression.kt SearchFilter.init',
    violate: () => filter.search('   '),
  },
  {
    wow: 'op and operator cannot be used together.',
    source: 'wow-api FilterExpression.kt FilterExpressionTypeDeserializer',
    byConstruction:
      'The builders emit `op` only; the legacy `operator` spelling belongs to the deprecated Condition API and is never mixed into a FilterExpression.',
  },
  {
    wow: 'Nested filter expression must use op.',
    source: 'wow-api FilterExpression.kt requireCanonicalFilterPayload',
    byConstruction: 'Every nested operand is built by the same builders.',
  },
  {
    wow: 'Filter expression properties must use op.',
    source: 'wow-api FilterExpression.kt FilterExpressionTypeDeserializer',
    byConstruction: 'Same: there is no way to emit the legacy spelling.',
  },
  {
    wow: '$operator value must be a JSON scalar in filter payloads.',
    source: 'wow-api FilterExpression.kt requireScalarEqualityValue',
    violate: () => filter.eq('a', [] as never),
    throws: 'Filter value must be a JSON scalar.',
  },

  // ---- MetadataFilters --------------------------------------------------
  {
    wow: 'IDS values cannot be empty.',
    source: 'wow-api MetadataFilters.kt IdsFilter.init',
    violate: () => filter.ids([]),
  },
  {
    wow: 'AGGREGATE_IDS values cannot be empty.',
    source: 'wow-api MetadataFilters.kt AggregateIdsFilter.init',
    violate: () => filter.aggregateIds([]),
  },

  // ---- PredicateFilters -------------------------------------------------
  {
    wow: 'Filter value must be a JSON scalar.',
    source: 'wow-api PredicateFilters.kt requireFilterLiteral',
    violate: () => filter.eq('a', {} as never),
  },
  {
    wow: 'EQ/NE value must be a JSON scalar, scalar array, or runtime POJO.',
    source: 'wow-api PredicateFilters.kt requireEqualityFilterValue',
    violate: () => filter.ne('a', {} as never),
    throws: 'Filter value must be a JSON scalar.',
  },
  {
    wow: 'Comparison filter value cannot be null.',
    source: 'wow-api PredicateFilters.kt requireComparableFilterLiteral',
    violate: () => filter.gt('a', null as never),
    throws: 'Filter value must be a JSON scalar.',
  },
  {
    wow: '$operator values cannot be empty.',
    source: 'wow-api PredicateFilters.kt requireFilterLiterals',
    violate: () => filter.isIn('a', []),
    throws: 'IN values cannot be empty.',
  },
  {
    wow: '$operator values cannot contain null.',
    source: 'wow-api PredicateFilters.kt requireFilterLiterals',
    violate: () => filter.containsAll('a', [null as never]),
    throws: 'CONTAINS_ALL values cannot contain null.',
  },

  // ---- RelativeTimeFilters ----------------------------------------------
  {
    wow: 'zoneId cannot be blank.',
    source: 'wow-api RelativeTimeFilters.kt requireZoneId',
    violate: () => filter.today('a', { zoneId: '  ' }),
  },
  {
    wow: 'datePattern cannot be blank.',
    source: 'wow-api RelativeTimeFilters.kt toDateFormatter',
    violate: () => filter.thisWeek('a', { datePattern: '  ' }),
  },
  {
    wow: 'RECENT_DAYS days must be greater than zero.',
    source: 'wow-api RelativeTimeFilters.kt RecentDaysFilter.init',
    violate: () => filter.recentDays('a', 0),
    throws: 'RECENT_DAYS days must be a positive JVM Int.',
  },
  {
    wow: 'EARLIER_DAYS days must be greater than zero.',
    source: 'wow-api RelativeTimeFilters.kt EarlierDaysFilter.init',
    violate: () => filter.earlierDays('a', 0),
    throws: 'EARLIER_DAYS days must be a positive JVM Int.',
  },

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
            { type: 'CONSTANT', value: Number.NaN } as DerivedExpression,
            'd',
          ),
        ),
      }),
  },
  {
    wow: 'percentile must be finite and within (0, 100).',
    source: 'wow-api AggregationQuery.kt AggregationMetric.Percentile.init',
    violate: () => aggregation.percentile(aggregation.constant(1), 100, 'p'),
  },
  {
    wow: 'histogram interval must be finite and greater than 0.',
    source: 'wow-api AggregationQuery.kt AggregationGroup.Histogram.init',
    violate: () => aggregation.histogram('a', { interval: 0, alias: 'h' }),
  },
  {
    wow: 'terms missingKey must not be blank.',
    source: 'wow-api AggregationQuery.kt AggregationGroup.Terms.init',
    violate: () => aggregation.terms('a', 'g', '  '),
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
          aggregation.dateHistogram('t', {
            unit: 'DAY' as never,
            alias: 'd',
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
            { type: 'METRIC_REF', metric: 'later' } as DerivedExpression,
            'd',
          ),
          count('later'),
        ),
      }),
    throws:
      'derived metric [d] must reference a metric declared before it, but was [later].',
  },
  {
    wow: 'derived metric [$alias] cannot reference ANY metric [$reference].',
    source: 'wow-api AggregationQuery.kt requireValidDerivedExpression',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.any('a', 'sample'),
          aggregation.derived(
            { type: 'METRIC_REF', metric: 'sample' } as DerivedExpression,
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
          ? { type: 'CONSTANT', value: 1 }
          : ({
              type: 'BINARY',
              operator: 'ADD',
              left: chain(d - 1),
              right: { type: 'CONSTANT', value: 1 },
            } as DerivedExpression);
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
          ? { type: 'CONSTANT', value: 1 }
          : ({
              type: 'BINARY',
              operator: 'ADD',
              left: tree(d - 1),
              right: tree(d - 1),
            } as DerivedExpression);
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
        having: { type: 'IS_NULL', metric: 'm' } as HavingExpression,
      }),
  },
  {
    wow: 'having condition [$metric] must reference a declared metric alias.',
    source: 'wow-api AggregationQuery.kt requireValidHavingMetric',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: { type: 'IS_NULL', metric: 'nope' } as HavingExpression,
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
        having: { type: 'IS_NULL', metric: 'sample' } as HavingExpression,
      }),
    throws: 'having condition [sample] cannot reference ANY metric.',
  },
  {
    wow: 'having condition [${current.metric}] value must be finite.',
    source: 'wow-api AggregationQuery.kt requireValidHaving',
    violate: () =>
      aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: {
          type: 'CONDITION',
          metric: 'm',
          operator: 'GT',
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
          type: 'BETWEEN',
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
          type: 'BETWEEN',
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
          type: 'IN',
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
          type: 'IN',
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
          type: 'AND',
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
          type: 'OR',
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
          ? ({ type: 'IS_NULL', metric: 'm' } as HavingExpression)
          : ({ type: 'AND', operands: [nest(d - 1)] } as HavingExpression);
      return aggregation.query({
        groupBy: grouped,
        metrics: anyMetrics(count('m')),
        having: nest(9),
      });
    },
    throws: 'having expression depth must be at most 8.',
  },

  // ---- CursorQuery ------------------------------------------------------
  {
    wow: 'size must be between 1 and ${Int.MAX_VALUE - 1}.',
    source: 'wow-api CursorQuery.kt CursorQuery.init',
    violate: () => cursorQuery({ filter: filter.matchAll(), size: 0 }),
    throws: 'size must be between 1 and 2147483646.',
  },
  {
    wow: 'Cursor sort fields must be unique.',
    source: 'wow-query CursorQueries.kt withUniqueSort',
    violate: () =>
      cursorQuery({
        filter: filter.matchAll(),
        sort: [asc('a'), { field: 'a', direction: SortDirection.DESC }],
      }),
  },

  // ---- Sort / Projection ------------------------------------------------
  {
    wow: 'Query field is invalid: [$path]. (Sort.field)',
    source: 'wow-api Queryable.kt Sort(field: QueryField, …)',
    violate: () => asc('9 not a path'),
    throws: 'Query field is invalid: [9 not a path].',
  },
  {
    wow: 'Query field is invalid: [$path]. (Projection.include/exclude)',
    source:
      'wow-api Queryable.kt Projection(include/exclude: List<QueryField>)',
    violate: () => projection({ include: ['9 not a path'] }),
    throws: 'Query field is invalid: [9 not a path].',
  },

  {
    wow: 'Unsupported aggregation expression: ${expression::class.java.name}.',
    source: 'wow-api AggregationQuery.kt requireValidExpressions',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(aggregation.sum({ type: 'WINDOW' } as never, 'm')),
      }),
    throws: 'Unsupported aggregation expression: WINDOW.',
  },

  // ---- The deprecated Condition shape, which only the server reads -------
  {
    wow: 'Operator must be DELETED, but was $operator.',
    source: 'wow-api Condition.kt deletionState',
    byConstruction:
      '`deletionState()` reads a stored Condition. `deleted()` here always pairs the DELETED operator with its value, and the whole Condition API is deprecated in favour of `filter.deletion`.',
  },
  {
    wow: 'Value must be String, Boolean, or DeletionState, but was ${value::class.simpleName}.',
    source: 'wow-api Condition.kt deletionState',
    byConstruction: '`deleted()` takes a DeletionState and emits it unchanged.',
  },
  {
    wow: 'datePatternOptions value must be String or DateTimeFormatter',
    source: 'wow-api Condition.kt datePatternOptions',
    byConstruction:
      'The date condition builders take a string pattern; there is no DateTimeFormatter in TypeScript to confuse it with.',
  },

  // ---- Left to the server ------------------------------------------------
  {
    wow: 'Unsupported legacy query type: ${inputType.name}.',
    source: 'wow-api QueryJsonDeserializer.kt',
    serverOnly:
      'Raised while reading a stored query of the deprecated shape. This package writes queries and never deserialises one.',
  },
  {
    wow: 'Query schema identifier is invalid: [$value].',
    source: 'wow-api schema/QuerySchemaTypes.kt',
    serverOnly:
      'Names identifiers inside an aggregate schema, which this package neither holds nor sends.',
  },
  {
    wow: 'Temporal pattern cannot be blank.',
    source: 'wow-api schema/QueryTemporal.kt',
    serverOnly:
      'A schema-declared temporal pattern. A filter carries `datePattern`, which is checked at build time above.',
  },
  {
    wow: 'Aggregation metric filters do not support search filters.',
    source: 'wow-query MetricFilterValidation.kt',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.count('m', filter.search('x') as FilterExpression),
        ),
      }),
  },
  {
    wow: 'Aggregation metric filters do not support [ELEMENT_MATCH].',
    source: 'wow-query MetricFilterValidation.kt',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.count(
            'm',
            filter.elementMatch('i', filter.eq('a', 1)) as FilterExpression,
          ),
        ),
      }),
  },
  {
    wow: 'Aggregation metric filter field [$logical] must be scalar; array fields are not supported in metric filters.',
    source:
      'wow-query MetricFilterValidation.kt requireScalarMetricFilterField',
    serverOnly:
      'Needs the aggregate schema to know which fields are array-valued. `@ahoo-wang/fetcher-view-engine` does it from its own FieldKind registry; this package holds no schema.',
  },
  {
    wow: 'Field [$logical] does not support [$capability].',
    source: 'wow-query QuerySchemaValidation.kt',
    serverOnly: 'Needs the aggregate schema.',
  },
  {
    wow: 'Field [$field] cannot be projected.',
    source: 'wow-query QueryFieldProtection.kt',
    serverOnly: 'Needs the schema and the caller’s permissions.',
  },
  {
    wow: 'Unknown logical field [$logical].',
    source: 'wow-query QuerySchemaValidation.kt',
    serverOnly: 'Needs the aggregate schema.',
  },
  {
    wow: 'Unknown relative-time field: [${input.field}].',
    source: 'wow-query FilterNormalizer.kt',
    serverOnly: 'Needs the aggregate schema to resolve the temporal value.',
  },
  {
    wow: 'MongoDB permits one text expression and none beneath NOR.',
    source: 'wow-mongo AbstractMongoFilterCompiler.kt validateNativeText',
    serverOnly:
      'A MongoDB rule, not a protocol one: the same query is accepted against Elasticsearch, and the client does not know the backend.',
  },
  {
    wow: 'MongoDB PHRASE search query cannot contain double quotes.',
    source: 'wow-mongo AbstractMongoFilterCompiler.kt',
    serverOnly: 'Backend-specific, as above.',
  },
];

describe('Wow query conformance', () => {
  const mirrored = RULES.filter(rule => rule.violate);
  const declared = RULES.filter(rule => !rule.violate);

  // A rule is what Wow states *somewhere*: `Query field is invalid` is stated
  // by QueryField, by Sort and by Projection, and the register carries one
  // entry each. Keying on the message alone would fold them into one and lose
  // two of the three without anything noticing.
  const identity = (rule: ConformanceRule) => `${rule.source} :: ${rule.wow}`;

  it('names every rule exactly once', () => {
    const names = RULES.map(identity);
    expect(new Set(names).size).toBe(names.length);
  });

  it('cites a distinct source for rules that share a message', () => {
    const byMessage = new Map<string, string[]>();
    for (const rule of RULES)
      byMessage.set(rule.wow, [
        ...(byMessage.get(rule.wow) ?? []),
        rule.source,
      ]);
    for (const [, sources] of byMessage)
      expect(new Set(sources).size).toBe(sources.length);
  });

  it('gives a reason for every rule it does not exercise', () => {
    expect(
      declared
        .filter(rule => !rule.byConstruction && !rule.serverOnly)
        .map(rule => rule.wow),
    ).toEqual([]);
  });

  it.each(mirrored.map(rule => [rule.wow, rule] as const))(
    'refuses what Wow refuses: %s',
    (_name, rule) => {
      expect(rule.violate).toThrow(rule.throws ?? rule.wow);
    },
  );

  it('covers the rules this package is answerable for', () => {
    // A floor, so deleting a rule from the register is visible in the diff
    // rather than silently reducing what is checked.
    expect(mirrored.length).toBeGreaterThanOrEqual(50);
  });
});
