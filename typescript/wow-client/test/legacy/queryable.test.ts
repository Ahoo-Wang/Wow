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

import { describe, expect, expectTypeOf, it } from 'vitest';
import type { FieldSort, Projection, QueryApi } from '../../src';
import { asc, DEFAULT_PAGINATION } from '../../src';
import type { ListQuery, PagedQuery, SingleQuery } from '../../src/legacy';
import { all, eq, listQuery, pagedQuery, singleQuery } from '../../src/legacy';

describe('legacy queryable', () => {
  it('builds Condition queries', () => {
    expectTypeOf(singleQuery()).toEqualTypeOf<SingleQuery<string>>();
    expectTypeOf(listQuery()).toEqualTypeOf<ListQuery<string>>();
    expectTypeOf(pagedQuery()).toEqualTypeOf<PagedQuery<string>>();
  });

  it('is what the root query clients still accept', () => {
    const accepts = (api: QueryApi<unknown>) => {
      void api.single(singleQuery());
      void api.list(listQuery());
      void api.paged(pagedQuery());
      void api.count(all());
    };
    void accepts;
  });

  it('defaults to all(), a limit of a page and the first page', () => {
    expect(singleQuery()).toEqual({ condition: all() });
    expect(listQuery()).toEqual({
      condition: all(),
      limit: DEFAULT_PAGINATION.size,
    });
    expect(pagedQuery()).toEqual({
      condition: all(),
      pagination: DEFAULT_PAGINATION,
    });
    expect(pagedQuery().pagination).not.toBe(DEFAULT_PAGINATION);
  });

  it('refuses a null condition', () => {
    for (const createQuery of [singleQuery, listQuery, pagedQuery]) {
      expect(() => createQuery({ condition: null } as never)).toThrowError(
        'condition cannot be null.',
      );
    }
  });

  it('passes every option through', () => {
    const condition = eq('name', 'test');
    const projection: Projection = { include: ['field1', 'field2'] };
    const sort: FieldSort[] = [asc('name')];
    expect(singleQuery({ condition, projection, sort })).toEqual({
      condition,
      projection,
      sort,
    });
    expect(listQuery({ condition, projection, sort, limit: 5 })).toEqual({
      condition,
      projection,
      sort,
      limit: 5,
    });
    const pagination = { index: 2, size: 20 };
    expect(pagedQuery({ condition, projection, sort, pagination })).toEqual({
      condition,
      projection,
      sort,
      pagination,
    });
  });
});
