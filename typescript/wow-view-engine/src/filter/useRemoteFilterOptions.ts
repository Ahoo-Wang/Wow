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

import { useEffect, useRef, useState } from 'react';
import {
  useExecutePromise,
  useDebouncedCallback,
  PromiseStatus,
} from '@ahoo-wang/fetcher-react/core';
import { DEFAULT_CURSOR_SIZE } from '@ahoo-wang/fetcher-wow';
import type {
  FilterOptionSource,
  FilterOptionValue,
  FilterOptionItem,
} from './filterOptionSource.js';
import {
  readFilterOptionPage,
  readResolvedFilterOptions,
} from './filterOptionSource.js';

export function useRemoteFilterOptions(
  source: FilterOptionSource,
  values: readonly FilterOptionValue[],
  debounceMs = 300,
  size = DEFAULT_CURSOR_SIZE,
) {
  const [search, setSearch] = useState('');
  const composing = useRef(false);
  const [data, setData] = useState<{
    list: FilterOptionItem[];
    nextCursor: string | null;
    consumed: (string | null)[];
    pending?: string | null;
    loaded: boolean;
  }>({ list: [], nextCursor: null, consumed: [], loaded: false });
  const [labels, setLabels] = useState<FilterOptionItem[]>([]);
  const [missing, setMissing] = useState<FilterOptionValue[]>([]);
  const query = useExecutePromise<
    { page: ReturnType<typeof readFilterOptionPage>; cursor: string | null },
    unknown
  >({
    onSuccess: ({ page, cursor }) => {
      setData(previous => ({
        list: [
          ...new Map(
            [...(cursor === null ? [] : previous.list), ...page.list].map(
              item => [item.value, item],
            ),
          ).values(),
        ],
        nextCursor: page.nextCursor,
        consumed: [...(cursor === null ? [] : previous.consumed), cursor],
        loaded: true,
      }));
    },
  });
  const hydration = useExecutePromise<
    ReturnType<typeof readResolvedFilterOptions>,
    unknown
  >({
    onSuccess: result => {
      setLabels(result.list);
      setMissing(result.missing);
    },
  });
  const valueKey = JSON.stringify(values);
  const { execute: hydrate, abort: abortHydration } = hydration;
  useEffect(() => {
    const ids: FilterOptionValue[] = JSON.parse(valueKey);
    if (ids.length)
      void hydrate(async controller =>
        readResolvedFilterOptions(
          await source.resolve(ids, controller.signal),
          ids,
        ),
      );
    return () => {
      abortHydration();
    };
  }, [valueKey, source, hydrate, abortHydration]);
  function load(keyword: string, cursor: string | null) {
    setData(previous => ({ ...previous, pending: cursor }));
    void query.execute(async controller => {
      const page = readFilterOptionPage(
        await source.search(
          { search: keyword, cursor, size },
          controller.signal,
        ),
      );
      if (
        page.nextCursor !== null &&
        (page.nextCursor === cursor ||
          (cursor !== null && data.consumed.includes(page.nextCursor)))
      )
        throw new TypeError('候选服务返回了重复分页游标');
      return { page, cursor };
    });
  }
  const debounce = useDebouncedCallback(
    (keyword: string) => load(keyword, null),
    { delay: debounceMs, leading: false, trailing: true },
  );
  function change(keyword: string, isComposing = composing.current) {
    setSearch(keyword);
    debounce.cancel();
    query.abort();
    setData({ list: [], nextCursor: null, consumed: [], loaded: false });
    if (!isComposing) {
      if (debounceMs === 0) load(keyword, null);
      else debounce.run(keyword);
    }
  }
  return {
    search,
    options: data.list,
    labels,
    missing,
    loading: query.loading,
    error: query.error,
    failed: query.status === PromiseStatus.ERROR,
    hydrationError: hydration.error,
    hydrationFailed: hydration.status === PromiseStatus.ERROR,
    hydrating: hydration.loading,
    nextCursor: data.nextCursor,
    change,
    compositionStart: () => {
      composing.current = true;
      debounce.cancel();
      query.abort();
    },
    compositionEnd: (keyword: string) => {
      composing.current = false;
      change(keyword, false);
    },
    open: (open: boolean) => {
      if (!open) {
        debounce.cancel();
        query.abort();
      } else if (!data.loaded || data.pending !== undefined)
        load(search, data.pending ?? null);
    },
    more: () => {
      if (!query.loading && data.nextCursor) load(search, data.nextCursor);
    },
    retry: () => {
      if (!query.loading) load(search, data.pending ?? null);
    },
    retryLabels: () => {
      const ids: FilterOptionValue[] = JSON.parse(valueKey);
      void hydrate(async controller =>
        readResolvedFilterOptions(
          await source.resolve(ids, controller.signal),
          ids,
        ),
      );
    },
  };
}
