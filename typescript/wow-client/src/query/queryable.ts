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

import { all, type ConditionCapable } from './condition';
import { type SortCapable } from './sort';
import { DEFAULT_PAGINATION, type Pagination } from './pagination';
import { type ProjectionCapable } from './projection';

/**
 * Interface for queryable objects that support conditions, projection, and sorting.
 */
export interface Queryable
  extends ConditionCapable,
    ProjectionCapable,
    SortCapable {
}

/**
 * Interface for single query objects.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SingleQuery extends Queryable {
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
export function singleQuery({
                              condition = all(),
                              projection,
                              sort,
                            }: Partial<SingleQuery> = {}): SingleQuery {
  return {
    condition,
    projection,
    sort,
  };
}

/**
 * Interface for list query objects.
 *
 * Limit the number of results. Default: DEFAULT_PAGINATION.size
 */
export interface ListQuery extends Queryable {
  limit?: number;
}

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
 * @param limit - The maximum number of results to return. Defaults to DEFAULT_PAGINATION.size.
 * @returns A ListQuery object with the specified parameters
 */
export function listQuery({
                            condition = all(),
                            projection,
                            sort,
                            limit = DEFAULT_PAGINATION.size,
                          }: Partial<ListQuery> = {}): ListQuery {
  return {
    condition,
    projection,
    sort,
    limit,
  };
}

/**
 * Interface for paged query objects.
 */
export interface PagedQuery extends Queryable {
  pagination?: Pagination;
}

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
export function pagedQuery({
                             condition = all(),
                             projection,
                             sort,
                             pagination = DEFAULT_PAGINATION,
                           }: Partial<PagedQuery> = {}): PagedQuery {
  return {
    condition,
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
