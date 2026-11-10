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
  asc,
  cursorQuery,
  filter,
  projection,
  SortDirection,
} from '../../../src/dsl';
import { anyMetrics, type ConformanceRule } from './register';

/** Wow's rules for a cursor query, a sort and a projection. */
export const pagingRules: ConformanceRule[] = [
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
];
