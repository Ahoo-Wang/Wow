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

import { all, type Condition, type ConditionCapable } from './condition';
import type { FilterCapable, FilterExpression } from './filter';
import { type SortCapable } from './sort';
import { DEFAULT_PAGINATION, type Pagination } from './pagination';
import { type ProjectionCapable } from './projection';

/**
 * Interface for queryable objects that support conditions, projection, and sorting.
 */
/** @deprecated Use FilterQueryable instead. */
export interface Queryable<FIELDS extends string = string>
  extends
    ConditionCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}

/** Queryable request using Wow's FilterExpression API. */
export interface FilterQueryable<FIELDS extends string = string>
  extends
    FilterCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}

/**
 * Interface for single query objects.
 */
/** @deprecated Use FilterSingleQuery instead. */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SingleQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {}

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface FilterSingleQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {}

export type SingleQueryRequest<FIELDS extends string = string> =
  SingleQuery<FIELDS> | FilterSingleQuery<FIELDS>;

type QueryFilterOptions<FIELDS extends string> = {
  condition?: Condition<FIELDS>;
  filter?: FilterExpression<FIELDS>;
};

type FilterQueryOptions<Q extends { filter: unknown }> = Pick<Q, 'filter'> &
  Partial<Omit<Q, 'filter'>>;

function queryFilter<FIELDS extends string>({
  condition,
  filter,
}: QueryFilterOptions<FIELDS>):
  ConditionCapable<FIELDS> | FilterCapable<FIELDS> {
  if (filter === null) {
    throw new TypeError('filter cannot be null.');
  }
  if (filter !== undefined) {
    return { filter };
  }
  if (condition === null) {
    throw new TypeError('condition cannot be null.');
  }
  return { condition: condition === undefined ? all() : condition };
}

/**
 * Creates a SingleQuery object with the provided parameters.
 *
 * This function is a factory for creating SingleQuery objects, which represent
 * queries that return a single result. It provides default values for optional
 * properties while allowing customization of condition, projection, and sort criteria.
 *
 * @param condition - The query condition. Defaults to an 'all' condition that matches everything.
 * @param projection - The field projection specification. Optional.
 * @param sort - The sort criteria. Optional.
 * @returns A SingleQuery object with the specified parameters
 */
export function singleQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterSingleQuery<FIELDS>>,
): FilterSingleQuery<FIELDS>;
/** @deprecated Pass filter instead of condition. */
export function singleQuery<FIELDS extends string = string>(
  options?: Partial<SingleQuery<FIELDS>>,
): SingleQuery<FIELDS>;
export function singleQuery<FIELDS extends string = string>({
  condition,
  filter,
  projection,
  sort,
}: QueryFilterOptions<FIELDS> &
  Partial<ProjectionCapable<FIELDS> & SortCapable<FIELDS>> = {}):
  SingleQuery<FIELDS> | FilterSingleQuery<FIELDS> {
  return {
    ...queryFilter({ condition, filter }),
    projection,
    sort,
  };
}

/**
 * Interface for list query objects.
 *
 * Limit the number of results. Default: DEFAULT_PAGINATION.size
 */
/** @deprecated Use FilterListQuery instead. */
export interface ListQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  limit?: number;
}

export interface FilterListQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  /** Maximum results. Defaults to 0 (unlimited) for FilterExpression queries. */
  limit?: number;
}

export type ListQueryRequest<FIELDS extends string = string> =
  ListQuery<FIELDS> | FilterListQuery<FIELDS>;

/**
 * Creates a ListQuery object with the provided parameters.
 *
 * This function is a factory for creating ListQuery objects, which represent
 * queries that return a list of results. It provides default values for optional
 * properties while allowing customization of condition, projection, sort criteria,
 * and result limit.
 *
 * @param condition - The query condition. Defaults to an 'all' condition that matches everything.
 * @param projection - The field projection specification. Optional.
 * @param sort - The sort criteria. Optional.
 * @param limit - The maximum number of results. Defaults to 0 for filter queries and DEFAULT_PAGINATION.size for legacy condition queries.
 * @returns A ListQuery object with the specified parameters
 */
export function listQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterListQuery<FIELDS>>,
): FilterListQuery<FIELDS>;
/** @deprecated Pass filter instead of condition. */
export function listQuery<FIELDS extends string = string>(
  options?: Partial<ListQuery<FIELDS>>,
): ListQuery<FIELDS>;
export function listQuery<FIELDS extends string = string>({
  condition,
  filter,
  projection,
  sort,
  limit,
}: QueryFilterOptions<FIELDS> &
  Partial<ProjectionCapable<FIELDS> & SortCapable<FIELDS>> & {
    limit?: number;
  } = {}): ListQuery<FIELDS> | FilterListQuery<FIELDS> {
  return {
    ...queryFilter({ condition, filter }),
    projection,
    sort,
    limit: limit ?? (filter === undefined ? DEFAULT_PAGINATION.size : 0),
  };
}

/**
 * Interface for paged query objects.
 */
/** @deprecated Use FilterPagedQuery instead. */
export interface PagedQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  pagination?: Pagination;
}

export interface FilterPagedQuery<
  FIELDS extends string = string,
> extends FilterQueryable<FIELDS> {
  pagination?: Pagination;
}

export type PagedQueryRequest<FIELDS extends string = string> =
  PagedQuery<FIELDS> | FilterPagedQuery<FIELDS>;

/**
 * Creates a PagedQuery object with the provided parameters.
 *
 * This function is a factory for creating PagedQuery objects, which represent
 * queries that return a paged list of results. It provides default values for optional
 * properties while allowing customization of condition, projection, sort criteria,
 * and pagination.
 *
 * @param condition - The query condition. Defaults to an 'all' condition that matches everything.
 * @param projection - The field projection specification. Optional.
 * @param sort - The sort criteria. Optional.
 * @param pagination - The pagination specification. Optional.
 *
 * @returns A PagedQuery object with the specified parameters
 */
export function pagedQuery<FIELDS extends string = string>(
  options: FilterQueryOptions<FilterPagedQuery<FIELDS>>,
): FilterPagedQuery<FIELDS>;
/** @deprecated Pass filter instead of condition. */
export function pagedQuery<FIELDS extends string = string>(
  options?: Partial<PagedQuery<FIELDS>>,
): PagedQuery<FIELDS>;
export function pagedQuery<FIELDS extends string = string>({
  condition,
  filter,
  projection,
  sort,
  pagination = DEFAULT_PAGINATION,
}: QueryFilterOptions<FIELDS> &
  Partial<ProjectionCapable<FIELDS> & SortCapable<FIELDS>> & {
    pagination?: Pagination;
  } = {}): PagedQuery<FIELDS> | FilterPagedQuery<FIELDS> {
  return {
    ...queryFilter({ condition, filter }),
    projection,
    sort,
    pagination,
  };
}

/**
 * Interface for paged list results.
 */
export interface PagedList<T> {
  total: number;
  list: T[];
}

export const EMPTY_PAGED_LIST: PagedList<any> = {
  total: 0,
  list: [],
};

/**
 * Creates a PagedList object with the provided parameters.
 *
 * This function is a factory for creating PagedList objects, which represent
 * a page of results with total count information. It provides default values
 * for optional properties while allowing customization of total count and list data.
 *
 * @param total - The total number of items. Defaults to 0.
 * @param list - The array of items in the current page. Defaults to an empty array.
 * @returns A PagedList object with the specified parameters
 */
export function pagedList<T>({
  total,
  list = [],
}: Partial<PagedList<T>> = EMPTY_PAGED_LIST): PagedList<T> {
  if (total === undefined) {
    total = list.length;
  }
  return {
    total,
    list,
  };
}
