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
  DESC = 'DESC'
}

export interface Sort {
  field: string;
  direction: SortDirection;
}

export interface SortCapable {
  sort?: Sort[];
}

export interface Pagination {
  /**
   * 页码，从1开始
   */
  index: number;
  /**
   * 每页数量
   */
  size: number;
}

export const DEFAULT_PAGINATION: Pagination = {
  index: 1,
  size: 10,
};

export interface Projection {
  include?: string[];
  exclude?: string[];
}

export const DEFAULT_PROJECTION: Projection = {};

export interface ProjectionCapable {
  projection?: Projection;
}

export interface Queryable extends ConditionCapable, ProjectionCapable, SortCapable {

}

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SingleQuery extends Queryable {

}

export interface ListQuery extends Queryable {
  /**
   *  Limit the number of results.default:DEFAULT_PAGINATION.size
   */
  limit?: number;
}

export interface PagedQuery extends Queryable {
  pagination?: Pagination;
}

export interface PagedList<T> {
  total: number;
  list: T[];
}