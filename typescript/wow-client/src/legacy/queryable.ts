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

import { all, type ConditionCapable } from './condition.js';
import type { SortCapable } from '../dsl/sort.js';
import { DEFAULT_PAGINATION, type Pagination } from '../dsl/pagination.js';
import type { ProjectionCapable } from '../dsl/projection.js';

/**
 * A query that filters with a `Condition`, the only query model Wow before
 * 8.11 understands.
 *
 * @deprecated Use FilterQueryable instead. Removed in v10.
 */
export interface Queryable<FIELDS extends string = string>
  extends
    ConditionCapable<FIELDS>,
    ProjectionCapable<FIELDS>,
    SortCapable<FIELDS> {}

/** @deprecated Use FilterSingleQuery instead. Removed in v10. */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SingleQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {}

/** @deprecated Use FilterListQuery instead. Removed in v10. */
export interface ListQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  /** The most rows to return. `listQuery()` defaults it to `DEFAULT_PAGINATION.size`. */
  limit?: number;
}

/** @deprecated Use FilterPagedQuery instead. Removed in v10. */
export interface PagedQuery<
  FIELDS extends string = string,
> extends Queryable<FIELDS> {
  pagination?: Pagination;
}

function queryCondition<FIELDS extends string>(
  condition: ConditionCapable<FIELDS>['condition'] | undefined,
): ConditionCapable<FIELDS> {
  if (condition === null) {
    throw new TypeError('condition cannot be null.');
  }
  return { condition: condition ?? all() };
}

/**
 * Builds a Condition-based `single` query body. The condition defaults to
 * `all()`.
 *
 * @deprecated Use singleQuery from `@ahoo-wang/wow-client` with a filter instead. Removed in v10.
 */
export function singleQuery<FIELDS extends string = string>({
  condition,
  projection,
  sort,
}: Partial<SingleQuery<FIELDS>> = {}): SingleQuery<FIELDS> {
  return { ...queryCondition(condition), projection, sort };
}

/**
 * Builds a Condition-based `list` query body. The condition defaults to
 * `all()` and the limit to `DEFAULT_PAGINATION.size`.
 *
 * @deprecated Use listQuery from `@ahoo-wang/wow-client` with a filter instead. Removed in v10.
 */
export function listQuery<FIELDS extends string = string>({
  condition,
  projection,
  sort,
  limit = DEFAULT_PAGINATION.size,
}: Partial<ListQuery<FIELDS>> = {}): ListQuery<FIELDS> {
  return { ...queryCondition(condition), projection, sort, limit };
}

/**
 * Builds a Condition-based `paged` query body. The condition defaults to
 * `all()` and the pagination to a copy of `DEFAULT_PAGINATION`.
 *
 * @deprecated Use pagedQuery from `@ahoo-wang/wow-client` with a filter instead. Removed in v10.
 */
export function pagedQuery<FIELDS extends string = string>({
  condition,
  projection,
  sort,
  pagination = { ...DEFAULT_PAGINATION },
}: Partial<PagedQuery<FIELDS>> = {}): PagedQuery<FIELDS> {
  return { ...queryCondition(condition), projection, sort, pagination };
}
