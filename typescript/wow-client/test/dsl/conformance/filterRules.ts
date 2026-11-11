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

import { filter, type ElementFilterExpression } from '../../../src/dsl';
import { type ConformanceRule } from './register';

/**
 * Wow's rules for query fields and filters: the expressions, the metadata,
 * predicate and relative-time filters.
 */
export const filterRules: ConformanceRule[] = [
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
    wow: 'Unknown time-zone ID: $zoneId',
    source:
      'java.time ZoneRulesProvider.getProvider, from wow-api RelativeTimeFilters.kt requireZoneId',
    serverOnly:
      'Region IDs come from the tz database of the server’s JVM, which changes with its version. This package checks offset IDs (`+08:00`, `UTC+8`) only.',
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
  {
    // Stands for the whole of Duration.parse's grammar, which
    // test/dsl/filter.test.ts holds to values the JVM judged.
    wow: '$operator offset must be an ISO-8601 duration such as PT0S or -PT30M.',
    source: 'wow-api RelativeTimeFilters.kt requireOffset',
    violate: () => filter.afterNow('a', '30 minutes'),
    throws:
      'AFTER_NOW offset must be an ISO-8601 duration such as PT0S or -PT30M.',
  },
  {
    // The same message: requireOffset wraps whatever Duration.parse throws.
    wow: '$operator offset must be an ISO-8601 duration such as PT0S or -PT30M.',
    source:
      'java.time Duration.parse overflow, from wow-api RelativeTimeFilters.kt requireOffset',
    serverOnly:
      'An offset in the grammar whose value overflows a Duration (more than about 2^63 seconds) is refused by the server only; no query needs one.',
  },
];
