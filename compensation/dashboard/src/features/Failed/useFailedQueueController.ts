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
import type { PagedList, PagedQuery } from "@ahoo-wang/fetcher-wow";
import {
  all,
  and,
  desc,
  pagedList,
  pagedQuery,
  type Condition,
} from "@ahoo-wang/fetcher-wow";
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
  onSearch: (condition: Condition, hasFilters: boolean) => void;
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
  const [searchCondition, setSearchCondition] = useState<Condition>(() =>
    all(),
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
      condition: RetryConditions.categoryToCondition(category, Date.now()),
      sort: executionFailedSort(),
    }),
  );

  const {
    loading,
    result,
    error,
    isPending,
    setQuery: setRemoteQuery,
  } = useDebouncedQuery<PagedQuery, PagedList<ExecutionFailedState>, Error>({
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
    (nextQuery: PagedQuery) => {
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
    (nextSearchCondition: Condition, hasFilters: boolean) => {
      setQueryTransition("replace");
      setLastSuccessfulPage(undefined);
      setSearchCondition(nextSearchCondition);
      setHasSearchFilters(hasFilters);
      clearSelection();
      updateQuery(
        pagedQuery({
          condition: and(
            RetryConditions.categoryToCondition(category, Date.now()),
            nextSearchCondition,
          ),
          sort: executionFailedSort(),
        }),
      );
    },
    [category, clearSelection, updateQuery],
  );

  const clearFilters = useCallback(() => {
    setSearchResetToken((current) => current + 1);
    onSearch(all(), false);
  }, [onSearch]);

  const onPaginationChange = useCallback(
    (nextPage: number, nextPageSize: number) => {
      setQueryTransition("pagination");
      clearSelection();
      updateQuery({
        ...query,
        condition: and(
          RetryConditions.categoryToCondition(category, Date.now()),
          searchCondition,
        ),
        sort: executionFailedSort(),
        pagination: { index: nextPage, size: nextPageSize },
      });
    },
    [category, clearSelection, query, searchCondition, updateQuery],
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
      condition: and(
        RetryConditions.categoryToCondition(category, Date.now()),
        searchCondition,
      ),
      sort: executionFailedSort(),
    });
  }, [category, query, searchCondition, updateQuery]);

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
