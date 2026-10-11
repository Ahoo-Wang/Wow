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

import type { AggregationQuery } from '../../../dsl/aggregation/index.js';
import type { CursorPage, CursorQuery } from '../../../dsl/cursorQuery.js';
import type { DynamicDocument } from '../../../dsl/documents.js';
import type { FilterExpression } from '../../../dsl/filter/index.js';
import type {
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  PagedList,
} from '../../../dsl/queryable.js';
import type { QueryDescriptorResult } from '../descriptor/queryDescriptorApi.js';

/**
 * The query API of one dataset: a declared, table-like record set such as a BI
 * wide table, served by a Wow service under `dataset/{context}/{dataset}`.
 *
 * A dataset's records have no domain type, so each is a {@link DynamicDocument}
 * by default. The API also describes the dataset ({@link describe}), so one
 * client is a complete view-engine data source.
 *
 * @typeParam R - The record type, when the caller knows the dataset's columns.
 * @typeParam FIELDS - The field names a query may use.
 */
export interface DatasetQueryApi<
  R = DynamicDocument,
  FIELDS extends string = string,
> {
  /** Retrieves the first record in sort order, or fails with 404 when none matches. */
  single<T extends Partial<R> = R>(
    query: FilterSingleQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T>;

  /** Retrieves the matching records, at most the query's limit. */
  list<T extends Partial<R> = R>(
    query: FilterListQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;

  /** Streams the matching records, one per server-sent event. */
  listStream<T extends Partial<R> = R>(
    query: FilterListQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<T>>;

  /** Retrieves one page of records and the total. */
  paged<T extends Partial<R> = R>(
    query: FilterPagedQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<PagedList<T>>;

  /** Retrieves the next forward-only cursor page. */
  cursor<T extends Partial<R> = R>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<CursorPage<T>>;

  /** Counts the matching records. */
  count(
    filter: FilterExpression<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<number>;

  /** Runs an aggregation and returns all result rows. */
  aggregate<
    Row extends object = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<Row[]>;

  /** Runs an aggregation and streams the result rows, one per server-sent event. */
  aggregateStream<
    Row extends object = DynamicDocument,
    AGGREGATION_FIELDS extends string = string,
  >(
    query: AggregationQuery<FIELDS, AGGREGATION_FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<Row>>;

  /**
   * The dataset's capability descriptor. Given the version already held, an
   * unchanged descriptor answers `notModified` (the server answers 304).
   */
  describe(
    previous?: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<QueryDescriptorResult>;
}
