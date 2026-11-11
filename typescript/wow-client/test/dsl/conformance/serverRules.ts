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

import { aggregation, filter, type FilterExpression } from '../../../src/dsl';
import { anyMetrics, type ConformanceRule } from './register';

/**
 * Wow's rules that only the server applies: the deprecated Condition shape, and
 * what is left to the server.
 */
export const serverRules: ConformanceRule[] = [
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
    wow: 'A $key is lower-kebab like the built-ins: [$value] is not; write [${WireNames.of(value)}].',
    source: 'wow-api schema/QuerySchemaTypes.kt',
    serverOnly:
      'Names identifiers inside an aggregate schema, which this package neither holds nor sends.',
  },
  {
    wow: 'Temporal pattern cannot be blank.',
    source: 'wow-api schema/QueryTemporal.kt',
    serverOnly:
      'A schema-declared temporal pattern. A filter carries no pattern of its own: the server writes its bounds in the field’s.',
  },
  {
    wow: 'Aggregation metric filters do not support search filters.',
    source: 'wow-query QueryViolation.kt MetricFilterSearch',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.count('m', {
            filter: filter.search('x') as FilterExpression,
          }),
        ),
      }),
  },
  {
    wow: 'Aggregation metric filters do not support [ELEMENT_MATCH].',
    source: 'wow-query QueryViolation.kt MetricFilterElementMatch',
    violate: () =>
      aggregation.query({
        metrics: anyMetrics(
          aggregation.count('m', {
            filter: filter.elementMatch(
              'i',
              filter.eq('a', 1),
            ) as FilterExpression,
          }),
        ),
      }),
  },
  {
    wow: 'Aggregation metric filter field [$logical] must be scalar; array fields are not supported in metric filters.',
    source: 'wow-query QueryViolation.kt MetricFilterArrayField',
    serverOnly:
      'Needs the aggregate schema to know which fields are array-valued. `@ahoo-wang/fetcher-view-engine` does it from its own FieldKind registry; this package holds no schema.',
  },
  {
    wow: 'Field [${this.field}] must store a date or an epoch to be aggregated by time.',
    source: 'wow-query QueryViolation.kt TemporalAggregationUnsupported',
    serverOnly:
      'Needs the aggregate schema to know how the field stores its instants (DATE_HISTOGRAM, DATE_PART and DATE_DIFF alike).',
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
