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
  PagedList,
  PagedQuery,
  Condition,
  type Pagination,
  pagedQuery,
  Projection,
  FieldSort,
} from '@ahoo-wang/fetcher-wow';
import {
  useExecutePromise,
  UsePromiseStateOptions,
  PromiseState, useLatest,
} from '../core';
import { useCallback, useState } from 'react';

export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = unknown,
> extends UsePromiseStateOptions<PagedList<R>, E> {
  initialQuery: PagedQuery<FIELDS>;
  query: (
    pagedQuery: PagedQuery<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<PagedList<R>>;
  attributes?: Record<string, any>;
}

export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = unknown,
> extends PromiseState<PagedList<R>, E> {
  execute: () => Promise<E | PagedList<R>>;
  reset: () => void;
  setCondition: (condition: Condition<FIELDS>) => void;
  setProjection: (projection: Projection<FIELDS>) => void;
  setPagination: (pagination: Pagination) => void;
  setSort: (sort: FieldSort<FIELDS>[]) => void;
}

export function usePagedQuery<R, FIELDS extends string = string, E = unknown>(
  options: UsePagedQueryOptions<R, FIELDS, E>,
): UsePagedQueryReturn<R, FIELDS> {
  const { initialQuery } = options;
  const promiseState = useExecutePromise<PagedList<R>, E>(options);
  const [condition, setCondition] = useState(initialQuery.condition);
  const [pagination, setPagination] = useState(initialQuery.pagination);
  const [projection, setProjection] = useState(initialQuery.projection);
  const [sort, setSort] = useState(initialQuery.sort);
  const latestOptions = useLatest(options);
  const queryExecutor = useCallback(async (): Promise<PagedList<R>> => {
    const queryRequest = pagedQuery({
      condition,
      pagination,
      projection,
      sort,
    });
    return latestOptions.current.query(queryRequest, latestOptions.current.attributes);
  }, [condition, projection, pagination, sort, latestOptions]);

  const execute = useCallback(() => {
    return promiseState.execute(queryExecutor);
  }, [promiseState, queryExecutor]);

  return {
    ...promiseState,
    execute,
    setCondition,
    setProjection,
    setPagination,
    setSort,
  };
}
