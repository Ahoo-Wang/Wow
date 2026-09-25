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
 * Two groups, which `BindingError.name` locates differently:
 * - decoding the request body (`errorCode` `IllegalArgument`): `name` is the
 *   JSON path of the offending value, such as `filter.state` or `metrics[0]`,
 *   or `body` for the body as a whole;
 * - admission against the query model (`errorCode` `QuerySchemaValidation`):
 *   `name` is the absolute logical field path, such as `state.items.sku`, or
 *   `''` when the rule is about the model rather than one field.
 *
 * HTTP budget rejections (`HTTP list query limit[...]` and the like) carry no
 * binding error yet.
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
