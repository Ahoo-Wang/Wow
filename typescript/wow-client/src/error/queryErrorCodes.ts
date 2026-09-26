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
 * The codes a rejected query carries as `BindingError.code`: which rule the
 * request broke, stable where the human text (`errorMsg`) is not.
 *
 * Mirrors `QueryErrorCodes` in
 * `wow-api/src/main/kotlin/me/ahoo/wow/api/query/QueryErrorCodes.kt`, which the
 * server also publishes as the `BindingError.code` enum of its OpenAPI
 * document. The list is open: Wow adds codes and never renames or reuses one,
 * so a client treats a code it does not know as a generic rejection and shows
 * `errorMsg`. {@link QueryErrorCode} admits those codes too.
 *
 * Three groups, which `BindingError.name` locates differently:
 * - decoding the request body (`errorCode` `IllegalArgument`): `name` is the
 *   JSON path of the offending value, such as `filter.state` or `metrics[0]`,
 *   or `body` for the body as a whole;
 * - the entry's budget and gates (`errorCode` `IllegalArgument`): `name` is
 *   the part of the request the rule bounds, such as `limit` or `filter`;
 * - admission against the query model (`errorCode` `QuerySchemaValidation`):
 *   `name` is the absolute logical field path, such as `state.items.sku`, or
 *   `''` when the rule is about the model rather than one field.
 *
 * Budget and gate rejections carry a code from Wow 9.2 on; an older server
 * answers them with text alone (`HTTP list query limit[...] must be …`). A
 * failure of the server itself is no rejection: it answers HTTP 500
 * `InternalServerError` without a binding error.
 */
export const QueryErrorCodes = Object.freeze({
  /** The body is not valid JSON. */
  INVALID_JSON: 'INVALID_JSON',
  /** The body is JSON but not an object. */
  BODY_NOT_OBJECT: 'BODY_NOT_OBJECT',
  /** The body is empty. */
  EMPTY_BODY: 'EMPTY_BODY',
  /** The body has a property the query type does not declare. */
  UNKNOWN_PROPERTY: 'UNKNOWN_PROPERTY',
  /** An `op`, metric or other type discriminator names no known type. */
  UNKNOWN_TYPE: 'UNKNOWN_TYPE',
  /** An enum value is not one the server knows. */
  UNKNOWN_VALUE: 'UNKNOWN_VALUE',
  /** A value is missing or has the wrong JSON type. */
  INVALID_VALUE: 'INVALID_VALUE',
  /** Any other request rule; the message names it. */
  INVALID_REQUEST: 'INVALID_REQUEST',
  /** A cursor sort names a field twice; `path` is the repeated field. */
  CURSOR_SORT_DUPLICATE: 'CURSOR_SORT_DUPLICATE',
  /** A cursor sort has too many fields once the identity tie-breaker is appended. */
  CURSOR_SORT_TOO_MANY: 'CURSOR_SORT_TOO_MANY',
  /**
   * The cursor token was not issued for this model and effective sort, or
   * does not decode; `path` is `cursor`. Start again from the first page.
   */
  INVALID_CURSOR: 'INVALID_CURSOR',
  /**
   * The entry's budget: a list or aggregation `limit`, a page's index, size,
   * window or offset, or a cursor page's `size` is out of range; `path` names
   * which (`limit`, `pagination.size`, `size`, …).
   */
  SIZE_OUT_OF_RANGE: 'SIZE_OUT_OF_RANGE',
  /**
   * The entry's budget: the filter or `having` has too many nodes or values;
   * `path` is `filter` or `having`.
   */
  FILTER_TOO_LARGE: 'FILTER_TOO_LARGE',
  /**
   * The entry does not allow expensive operators, and the query uses one: an
   * operator, an element expansion, a metric sort, an arithmetic or
   * expression group, a date part, a dense fill or FIRST / LAST; `path` names
   * the part (`filter`, `elements`, `sort`, `metrics`, `groupBy`).
   */
  EXPENSIVE_OPERATOR_DISABLED: 'EXPENSIVE_OPERATOR_DISABLED',
  /**
   * A counting query (count, or a paged query's total) would match every
   * record, which the entry refuses; `path` is `filter`.
   */
  COUNT_REQUIRES_FILTER: 'COUNT_REQUIRES_FILTER',
  /**
   * A `having` or metric sort the storage evaluates after grouping read more
   * groups than the server allows; `path` is `body`. It may end a stream
   * partway through.
   */
  RESIDUAL_GROUPS_EXCEEDED: 'RESIDUAL_GROUPS_EXCEEDED',
  /**
   * An in-process query named no entry where one is required. Never
   * answered over HTTP; listed so the mirror stays complete.
   */
  EXPLICIT_ENTRY_REQUIRED: 'EXPLICIT_ENTRY_REQUIRED',
  /** The model has no such logical field. */
  UNKNOWN_FIELD: 'UNKNOWN_FIELD',
  /** The field does not offer the capability the query uses (sort, range, …). */
  UNSUPPORTED_CAPABILITY: 'UNSUPPORTED_CAPABILITY',
  /** The field is an element field and must be queried inside its element scope. */
  ELEMENT_SCOPE_REQUIRED: 'ELEMENT_SCOPE_REQUIRED',
  /** A filter value falls outside the field's declared domain. */
  VALUE_MISMATCH: 'VALUE_MISMATCH',
  /** The field is not a known collection (for example under `isEmpty`). */
  NOT_COLLECTION: 'NOT_COLLECTION',
  /** The field is not a single string. */
  NOT_SINGLE_STRING: 'NOT_SINGLE_STRING',
  /** The model does not support search. */
  MODEL_SEARCH_UNSUPPORTED: 'MODEL_SEARCH_UNSUPPORTED',
  /** The field cannot be used for a cursor. */
  CURSOR_NOT_ALLOWED: 'CURSOR_NOT_ALLOWED',
  /** The field is protected and cannot be aggregated. */
  PROTECTED_AGGREGATION: 'PROTECTED_AGGREGATION',
  /**
   * The field is protected and cannot be filtered or sorted: its descriptor
   * says `sensitivity.comparable: false`. A search that names it is refused
   * the same way.
   */
  PROTECTED_COMPARISON: 'PROTECTED_COMPARISON',
  /** A terms group's `missingKey` needs a single-valued string field. */
  MISSING_KEY_REQUIRES_STRING: 'MISSING_KEY_REQUIRES_STRING',
  /** `ANY` needs a single value. */
  ANY_REQUIRES_SINGLE_VALUE: 'ANY_REQUIRES_SINGLE_VALUE',
  /** The storage cannot deliver a complete projection; select fields explicitly. */
  INCOMPLETE_PROJECTION: 'INCOMPLETE_PROJECTION',
  /** A metric filter uses a search filter. */
  METRIC_FILTER_SEARCH: 'METRIC_FILTER_SEARCH',
  /** A metric filter uses `ELEMENT_MATCH`. */
  METRIC_FILTER_ELEMENT_MATCH: 'METRIC_FILTER_ELEMENT_MATCH',
  /** A metric filter names an array field; it must be scalar. */
  METRIC_FILTER_ARRAY_FIELD: 'METRIC_FILTER_ARRAY_FIELD',
  /** The field cannot be projected. */
  NOT_PROJECTABLE: 'NOT_PROJECTABLE',
  /** An event projection that keeps payloads must keep their `bodyType`. */
  EVENT_PROJECTION_TYPE_REQUIRED: 'EVENT_PROJECTION_TYPE_REQUIRED',
  /** A relative-time filter needs a field with a known temporal representation. */
  TEMPORAL_REPRESENTATION_REQUIRED: 'TEMPORAL_REPRESENTATION_REQUIRED',
  /** A relative-time filter's zone, pattern or unit conflicts with the field's definition. */
  TEMPORAL_CONFIGURATION_CONFLICT: 'TEMPORAL_CONFIGURATION_CONFLICT',
  /**
   * A sort names two array fields on independent arrays, which the storage
   * cannot order by together; `path` is the second of them. The descriptor
   * lists such fields under a `PARALLEL_ARRAY_SORT` constraint.
   */
  PARALLEL_ARRAY_SORT: 'PARALLEL_ARRAY_SORT',
  /**
   * An `EQ` or `NE` compares the field to an array, which the storage cannot
   * do: it takes only a scalar operand. The descriptor says so with an
   * `ARRAY_EQUALITY` constraint.
   */
  ARRAY_EQUALITY: 'ARRAY_EQUALITY',
  /**
   * A `FIRST` or `LAST` metric names a field that may hold several values;
   * it needs a single-valued one (`aggregate.firstLast` in the descriptor).
   */
  FIRST_LAST_REQUIRES_SINGLE_VALUE: 'FIRST_LAST_REQUIRES_SINGLE_VALUE',
  /**
   * A `FIRST` or `LAST` metric names no `orderBy` where there is no event
   * time to order by, such as inside an element or on a model whose
   * descriptor has no `analysis.firstLastOrderBy`; `path` is its field.
   */
  FIRST_LAST_REQUIRES_ORDER_BY: 'FIRST_LAST_REQUIRES_ORDER_BY',
  /**
   * A date group or date difference names a field that stores neither a
   * date nor an epoch (a formatted string, say), so it cannot be aggregated
   * by time.
   */
  TEMPORAL_AGGREGATION_UNSUPPORTED: 'TEMPORAL_AGGREGATION_UNSUPPORTED',
  /** The sort has more fields than the protocol allows; `path` is `''`. */
  SORT_TOO_MANY: 'SORT_TOO_MANY',
  /** Two sort fields name the same stored field; `path` is the second. */
  SORT_FIELD_DUPLICATE: 'SORT_FIELD_DUPLICATE',
  /**
   * An identity filter or a cursor on a model that defines no record
   * identity; `path` is `''`.
   */
  IDENTITY_UNDEFINED: 'IDENTITY_UNDEFINED',
  /**
   * The storage does not support the feature the query uses, or its native
   * query language cannot express it (a MongoDB PHRASE search with a quote,
   * say); `path` is the field, or `''`.
   */
  STORAGE_UNSUPPORTED: 'STORAGE_UNSUPPORTED',
} as const);

/** One of the codes this package knows; see {@link QueryErrorCodes}. */
export type KnownQueryErrorCode =
  (typeof QueryErrorCodes)[keyof typeof QueryErrorCodes];

/**
 * The code of a rejected query: one this package knows, which editors
 * complete, or one a newer server added.
 */
export type QueryErrorCode = KnownQueryErrorCode | (string & {});

/**
 * Why the server rejected a request, read from its first binding error that
 * carries a code: which rule failed, where, and the server's words.
 */
export interface QueryViolation {
  /** Which rule failed; switch on it against {@link QueryErrorCodes}. */
  code: QueryErrorCode;
  /**
   * Where: the JSON path of the offending value for a decoding error
   * (`filter.state`, `metrics[0]`, `body`), the absolute logical field path
   * for an admission error (`state.items.sku`), or `''` when the rule is about
   * the model rather than a field.
   */
  path: string;
  /** The server's words, the same as the error's `errorMsg`. */
  message: string;
}
