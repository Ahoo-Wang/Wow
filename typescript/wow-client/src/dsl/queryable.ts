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
  filter as filters,
  type FilterCapable,
  type FilterExpression,
} from './filter/index.js';
import { type SortCapable } from './sort.js';
import { DEFAULT_PAGINATION, type Pagination } from './pagination.js';
import { type ProjectionCapable } from './projection.js';

/** A query that filters, projects and sorts with Wow's `FilterExpression`. */
export interface FilterQueryable<FIELDS extends string = string>
  extends
    FilterCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}

/** The body of a `single` query: the first match of the filter, or nothing. */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface FilterSingleQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {}

/** The body of a `list` or `listStream` query. */
export interface FilterListQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  /**
   * The most rows to return. The client sends no default: absent (or 0)
   * leaves the choice to the server, and what the server does depends on its
   * version.
   *
   * - Wow 9.1.5 and later apply the server's default list size over HTTP
   *   (100 unless the server configures another).
   * - Wow 8.11 to 9.1.3 reject the query with `IllegalArgument: HTTP list
   *   query limit[0] must be between 1 and …` (HTTP 400 for a list, the error
   *   event that ends a list stream), so pass `limit` explicitly against
   *   those servers.
   *
   * Wow 8.11 and later also refuse a value above the server's maximum list
   * size (1000 by default).
   */
  limit?: number;
}

/** The body of a `paged` query. */
export interface FilterPagedQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  pagination?: Pagination;
}

/** What `singleQuery`, `listQuery` and `pagedQuery` take in common. */
type QueryOptions<FIELDS extends string = string> = Partial<
  FilterQueryable<FIELDS>
>;

function queryFilter<FIELDS extends string>(
  filter: FilterExpression<FIELDS> | undefined,
): FilterExpression<FIELDS> {
  if (filter === null) {
    throw new TypeError('filter cannot be null.');
  }
  return filter ?? filters.matchAll();
}

/**
 * Builds the body of a `single` query.
 *
 * @param options.filter - What to match. Defaults to `filter.matchAll()`.
 * @param options.projection - Which fields to return. Optional.
 * @param options.sort - Which match counts as the first. Optional.
 * @throws TypeError when `filter` is `null`.
 *
 * @example
 * ```typescript
 * singleQuery({ filter: filter.eq('status', 'ACTIVE') });
 * ```
 */
export function singleQuery<FIELDS extends string = string>({
  filter,
  projection,
  sort,
}: QueryOptions<FIELDS> = {}): FilterSingleQuery<FIELDS> {
  return { filter: queryFilter(filter), projection, sort };
}

/**
 * Builds the body of a `list` or `listStream` query.
 *
 * @param options.filter - What to match. Defaults to `filter.matchAll()`.
 * @param options.projection - Which fields to return. Optional.
 * @param options.sort - The order of the rows. Optional.
 * @param options.limit - The most rows to return. Absent is sent as absent:
 *   Wow 9.1.5 and later apply the server's default list size, Wow 8.11 to
 *   9.1.3 reject the query, so pass it for those servers; see
 *   {@link FilterListQuery.limit}.
 * @throws TypeError when `filter` is `null`.
 *
 * @example
 * ```typescript
 * listQuery({ filter: filter.eq('status', 'ACTIVE'), limit: 20 });
 * ```
 */
export function listQuery<FIELDS extends string = string>({
  filter,
  projection,
  sort,
  limit,
}: QueryOptions<FIELDS> & { limit?: number } = {}): FilterListQuery<FIELDS> {
  return { filter: queryFilter(filter), projection, sort, limit };
}

/**
 * Builds the body of a `paged` query.
 *
 * @param options.filter - What to match. Defaults to `filter.matchAll()`.
 * @param options.projection - Which fields to return. Optional.
 * @param options.sort - The order of the rows. Optional.
 * @param options.pagination - Which page. Defaults to a copy of
 *   {@link DEFAULT_PAGINATION}: the first page of ten.
 * @throws TypeError when `filter` is `null`.
 *
 * @example
 * ```typescript
 * pagedQuery({ filter: filter.matchAll(), pagination: { index: 2, size: 20 } });
 * ```
 */
export function pagedQuery<FIELDS extends string = string>({
  filter,
  projection,
  sort,
  pagination = { ...DEFAULT_PAGINATION },
}: QueryOptions<FIELDS> & {
  pagination?: Pagination;
} = {}): FilterPagedQuery<FIELDS> {
  return { filter: queryFilter(filter), projection, sort, pagination };
}

/** One page of a `paged` query: its rows, and how many match in total. */
export interface PagedList<T> {
  total: number;
  list: T[];
}

/**
 * Builds a `PagedList`, for a placeholder page or a test double.
 *
 * @param options.total - How many rows match in total. Defaults to the length
 *   of `list`.
 * @param options.list - The rows of this page. Defaults to a new empty array.
 */
export function pagedList<T>({
  total,
  list = [],
}: Partial<PagedList<T>> = {}): PagedList<T> {
  return { total: total ?? list.length, list };
}
