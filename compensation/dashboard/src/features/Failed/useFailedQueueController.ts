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

import { useDebouncedQuery } from "@ahoo-wang/fetcher-react";
import type {
  FilterExpression,
  FilterPagedQuery,
  PagedList,
} from "@ahoo-wang/fetcher-wow";
import { desc, filter, pagedList, pagedQuery } from "@ahoo-wang/fetcher-wow";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import {
  ExecutionFailedAggregatedFields,
  type ExecutionFailedState,
} from "../../generated";
import { queryExecutionFailedPage } from "../../services";
import { FindCategory } from "./FindCategory.ts";
import { RetryConditions } from "./RetryConditions.ts";
import { clearExecutionSelection, selectExecution } from "./selection.ts";

type QueryTransition = "replace" | "pagination" | "refresh";

interface UseFailedQueueControllerOptions {
  category: FindCategory;
  desktop: boolean;
  refreshPaused: boolean;
}

export interface FailedQueueController {
  activeId?: string | null;
  blockingError?: Error;
  clearFilters: () => void;
  clearSelection: () => void;
  displayedPageIndex: number;
  displayedPageSize: number;
  hasSearchFilters: boolean;
  mutationsDisabled: boolean;
  onPaginationChange: (page: number, pageSize: number) => void;
  onSearch: (filterExpression: FilterExpression, hasFilters: boolean) => void;
  page: PagedList<ExecutionFailedState>;
  refresh: () => void;
  searchResetToken: number;
  select: (state: ExecutionFailedState) => void;
  selectedId: string | null;
  selectedState?: ExecutionFailedState;
  staleError?: Error;
  suspendingSelection: boolean;
  transitioning: boolean;
}

const executionFailedSort = () => [
  desc(ExecutionFailedAggregatedFields.AGGREGATE_ID),
];

function isAbortError(error: Error): boolean {
  return (
    error.name === "AbortError" ||
    error.message.toLowerCase().includes("signal is aborted")
  );
}

export function useFailedQueueController({
  category,
  desktop,
  refreshPaused,
}: UseFailedQueueControllerOptions): FailedQueueController {
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedId = searchParams.get("id");
  const [searchFilter, setSearchFilter] = useState<FilterExpression>(() =>
    filter.matchAll(),
  );
  const [hasSearchFilters, setHasSearchFilters] = useState(false);
  const [searchResetToken, setSearchResetToken] = useState(0);
  const [queryTransition, setQueryTransition] =
    useState<QueryTransition>("replace");
  const [settledPagination, setSettledPagination] = useState({
    index: 1,
    size: 10,
  });
  const [lastSuccessfulPage, setLastSuccessfulPage] =
    useState<PagedList<ExecutionFailedState>>();
  const [query, setCurrentQuery] = useState(() =>
    pagedQuery({
      filter: RetryConditions.categoryToCondition(category, Date.now()),
      sort: executionFailedSort(),
    }),
  );

  const {
    loading,
    result,
    error,
    isPending,
    setQuery: setRemoteQuery,
  } = useDebouncedQuery<
    FilterPagedQuery,
    PagedList<ExecutionFailedState>,
    Error
  >({
    execute: queryExecutionFailedPage,
    query,
    debounce: {
      delay: 300,
      leading: true,
    },
    autoExecute: true,
    onSuccess: (nextResult) => {
      const pagination = query.pagination ?? { index: 1, size: 10 };
      setLastSuccessfulPage(nextResult);
      setSettledPagination(pagination);
      setQueryTransition("replace");
      const lastPage = Math.max(
        1,
        Math.ceil(nextResult.total / pagination.size),
      );
      if (pagination.index > lastPage) {
        updateQuery({
          ...query,
          sort: executionFailedSort(),
          pagination: { ...pagination, index: lastPage },
        });
      }
    },
  });
  const transitioning = loading || isPending();
  const visibleError = error && !isAbortError(error) ? error : undefined;
  const updateQuery = useCallback(
    (nextQuery: FilterPagedQuery) => {
      setRemoteQuery(nextQuery);
      setCurrentQuery(nextQuery);
    },
    [setRemoteQuery],
  );

  const preservingSettledPage =
    queryTransition !== "replace" &&
    lastSuccessfulPage !== undefined &&
    (transitioning || error !== undefined || result === undefined);
  const suspendingSelection =
    transitioning &&
    queryTransition === "pagination" &&
    lastSuccessfulPage !== undefined;
  const page =
    preservingSettledPage && lastSuccessfulPage
      ? lastSuccessfulPage
      : transitioning
        ? pagedList<ExecutionFailedState>()
        : (result ?? pagedList<ExecutionFailedState>());
  const staleError = preservingSettledPage ? visibleError : undefined;
  const blockingError = staleError ? undefined : visibleError;
  const mutationsDisabled =
    queryTransition !== "replace" &&
    (transitioning || visibleError !== undefined);
  const selectedState = useMemo(() => {
    if (suspendingSelection) {
      return undefined;
    }
    if (selectedId) {
      return page.list.find((state) => state.id === selectedId);
    }
    return desktop ? page.list[0] : undefined;
  }, [desktop, page.list, selectedId, suspendingSelection]);
  const activeId = suspendingSelection
    ? undefined
    : (selectedId ?? selectedState?.id);
  const pagination = query.pagination ?? { index: 1, size: 10 };
  const displayedPageIndex = preservingSettledPage
    ? settledPagination.index
    : pagination.index;
  const displayedPageSize = preservingSettledPage
    ? settledPagination.size
    : pagination.size;

  useEffect(() => {
    const firstState = page.list[0];
    if (!desktop || transitioning || selectedId || !firstState) {
      return;
    }
    setSearchParams(selectExecution(searchParams, firstState.id), {
      replace: true,
    });
  }, [
    desktop,
    page.list,
    searchParams,
    selectedId,
    setSearchParams,
    transitioning,
  ]);

  const clearSelection = useCallback(() => {
    setSearchParams(clearExecutionSelection(searchParams), { replace: true });
  }, [searchParams, setSearchParams]);

  const onSearch = useCallback(
    (nextSearchFilter: FilterExpression, hasFilters: boolean) => {
      setQueryTransition("replace");
      setLastSuccessfulPage(undefined);
      setSearchFilter(nextSearchFilter);
      setHasSearchFilters(hasFilters);
      clearSelection();
      updateQuery(
        pagedQuery({
          filter: filter.and([
            RetryConditions.categoryToCondition(category, Date.now()),
            nextSearchFilter,
          ]),
          sort: executionFailedSort(),
        }),
      );
    },
    [category, clearSelection, updateQuery],
  );

  const clearFilters = useCallback(() => {
    setSearchResetToken((current) => current + 1);
    onSearch(filter.matchAll(), false);
  }, [onSearch]);

  const onPaginationChange = useCallback(
    (nextPage: number, nextPageSize: number) => {
      setQueryTransition("pagination");
      clearSelection();
      updateQuery({
        ...query,
        filter: filter.and([
          RetryConditions.categoryToCondition(category, Date.now()),
          searchFilter,
        ]),
        sort: executionFailedSort(),
        pagination: { index: nextPage, size: nextPageSize },
      });
    },
    [category, clearSelection, query, searchFilter, updateQuery],
  );

  const select = useCallback(
    (state: ExecutionFailedState) => {
      setSearchParams(selectExecution(searchParams, state.id));
    },
    [searchParams, setSearchParams],
  );

  const refresh = useCallback(() => {
    setQueryTransition("refresh");
    updateQuery({
      ...query,
      filter: filter.and([
        RetryConditions.categoryToCondition(category, Date.now()),
        searchFilter,
      ]),
      sort: executionFailedSort(),
    });
  }, [category, query, searchFilter, updateQuery]);

  useEffect(() => {
    const timeSensitive = [
      FindCategory.ToRetry,
      FindCategory.Executing,
      FindCategory.NextRetry,
    ].includes(category);
    if (!timeSensitive) {
      return;
    }

    const refreshVisibleQueue = () => {
      if (
        document.visibilityState === "visible" &&
        !refreshPaused &&
        !transitioning
      ) {
        refresh();
      }
    };
    const timer = window.setInterval(refreshVisibleQueue, 30_000);
    document.addEventListener("visibilitychange", refreshVisibleQueue);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshVisibleQueue);
    };
  }, [category, refresh, refreshPaused, transitioning]);

  return {
    activeId,
    blockingError,
    clearFilters,
    clearSelection,
    displayedPageIndex,
    displayedPageSize,
    hasSearchFilters,
    mutationsDisabled,
    onPaginationChange,
    onSearch,
    page,
    refresh,
    searchResetToken,
    select,
    selectedId,
    selectedState,
    staleError,
    suspendingSelection,
    transitioning,
  };
}
