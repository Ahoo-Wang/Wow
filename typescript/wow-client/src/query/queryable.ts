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

import { ConditionCapable } from './condition';

export enum SortDirection {
  ASC = 'ASC',
  DESC = 'DESC',
}

/**
 * Interface for sort criteria.
 */
export interface Sort {
  field: string;
  direction: SortDirection;
}

/**
 * Interface for objects that support sorting.
 */
export interface SortCapable {
  sort?: Sort[];
}

/**
 * Interface for pagination information.
 *
 * Page number, starting from 1
 * Page size
 */
export interface Pagination {
  index: number;
  size: number;
}

export const DEFAULT_PAGINATION: Pagination = {
  index: 1,
  size: 10,
};

/**
 * Interface for field projection.
 */
export interface Projection {
  include?: string[];
  exclude?: string[];
}

export const DEFAULT_PROJECTION: Projection = {};

/**
 * Interface for objects that support field projection.
 */
export interface ProjectionCapable {
  projection?: Projection;
}

/**
 * Interface for queryable objects that support conditions, projection, and sorting.
 */
export interface Queryable
  extends ConditionCapable,
    ProjectionCapable,
    SortCapable {}

/**
 * Interface for single query objects.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SingleQuery extends Queryable {}

/**
 * Interface for list query objects.
 *
 * Limit the number of results. Default: DEFAULT_PAGINATION.size
 */
export interface ListQuery extends Queryable {
  limit?: number;
}

/**
 * Interface for paged query objects.
 */
export interface PagedQuery extends Queryable {
  pagination?: Pagination;
}

/**
 * Interface for paged list results.
 */
export interface PagedList<T> {
  total: number;
  list: T[];
}
