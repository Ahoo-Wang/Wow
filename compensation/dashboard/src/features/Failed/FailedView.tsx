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

import { useDebouncedFetcherQuery } from "@ahoo-wang/fetcher-react";
import type { PagedList, PagedQuery } from "@ahoo-wang/fetcher-wow";
import {
  all,
  and,
  pagedList,
  pagedQuery,
  type Condition,
} from "@ahoo-wang/fetcher-wow";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { ExecutionFailedState } from "../../generated";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { FailedSearch } from "./FailedSearch.tsx";
import { FailedTable } from "./FailedTable.tsx";
import { FindCategory } from "./FindCategory.ts";
import { RetryConditions } from "./RetryConditions.ts";
import { clearExecutionSelection, selectExecution } from "./selection.ts";
import { FailedDetails } from "./details/FailedDetails.tsx";
import { FetchingFailedDetails } from "./details/FetchingFailedDetails.tsx";

interface FailedViewProps {
  category: FindCategory;
}

const SPLIT_LAYOUT_KEY = "compensation-dashboard:failed-view-layout";
const DEFAULT_SPLIT_LAYOUT = {
  "execution-list": 40,
  "execution-details": 60,
};
type QueryTransition = "replace" | "pagination" | "refresh";

function loadSplitLayout(): Record<string, number> {
  try {
    const stored = window.localStorage.getItem(SPLIT_LAYOUT_KEY);
    if (!stored) {
      return DEFAULT_SPLIT_LAYOUT;
    }
    const layout = JSON.parse(stored) as Record<string, unknown>;
    const list = layout["execution-list"];
    const details = layout["execution-details"];
    if (
      typeof list !== "number" ||
      typeof details !== "number" ||
      list < 30 ||
      list > 52 ||
      Math.abs(list + details - 100) > 0.1
    ) {
      return DEFAULT_SPLIT_LAYOUT;
    }
    return { "execution-list": list, "execution-details": details };
  } catch {
    return DEFAULT_SPLIT_LAYOUT;
  }
}

function saveSplitLayout(layout: Record<string, number>) {
  try {
    window.localStorage.setItem(SPLIT_LAYOUT_KEY, JSON.stringify(layout));
  } catch {
    // Persisting a UI preference must never interrupt the workspace.
  }
}

function isAbortError(error: Error): boolean {
  return (
    error.name === "AbortError" ||
    error.message.toLowerCase().includes("signal is aborted")
  );
}

function EmptyDetails() {
  return (
    <div className="flex h-full items-center justify-center bg-slate-50 p-8 text-center">
      <div>
        <p className="text-sm font-medium text-slate-700">
          Select an execution
        </p>
        <p className="mt-1 text-xs text-slate-500">
          Failure context and compensation actions will appear here.
        </p>
      </div>
    </div>
  );
}

function LoadingPageDetails() {
  return (
    <div
      role="status"
      aria-label="Loading page details"
      className="flex h-full items-center justify-center bg-slate-50 p-8 text-center"
    >
      <div>
        <p className="text-sm font-medium text-slate-700">Loading page</p>
        <p className="mt-1 text-xs text-slate-500">
          The next executions will appear here shortly.
        </p>
      </div>
    </div>
  );
}

export default function FailedView({ category }: FailedViewProps) {
  const desktop = useMediaQuery("(min-width: 960px)");
  const mobileDetailsFocusRef = useRef<HTMLDivElement>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedId = searchParams.get("id");
  const [searchCondition, setSearchCondition] = useState<Condition>(() =>
    all(),
  );
  const [hasSearchFilters, setHasSearchFilters] = useState(false);
  const [searchResetToken, setSearchResetToken] = useState(0);
  const [splitLayout] = useState(loadSplitLayout);
  const [queryTransition, setQueryTransition] =
    useState<QueryTransition>("replace");
  const [settledPagination, setSettledPagination] = useState({
    index: 1,
    size: 10,
  });
  const [query, setCurrentQuery] = useState(() =>
    pagedQuery({
      condition: RetryConditions.categoryToCondition(category),
      sort: [],
    }),
  );

  const {
    loading,
    result,
    error,
    isPending,
    setQuery: setRemoteQuery,
  } = useDebouncedFetcherQuery<PagedQuery, PagedList<ExecutionFailedState>>({
    url: "/execution_failed/snapshot/paged/state",
    query,
    debounce: {
      delay: 300,
      leading: true,
    },
    autoExecute: true,
    onSuccess: (nextResult) => {
      const pagination = query.pagination ?? { index: 1, size: 10 };
      setSettledPagination(pagination);
      setQueryTransition("replace");
      const lastPage = Math.max(
        1,
        Math.ceil(nextResult.total / pagination.size),
      );
      if (pagination.index > lastPage) {
        updateQuery({
          ...query,
          sort: [],
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
    transitioning && queryTransition !== "replace" && result !== undefined;
  const suspendingSelection =
    transitioning && queryTransition === "pagination" && result !== undefined;
  const page =
    transitioning && !preservingSettledPage
      ? pagedList<ExecutionFailedState>()
      : (result ?? pagedList<ExecutionFailedState>());
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
  const pageIndex = pagination.index;
  const pageSize = pagination.size;
  const displayedPageIndex = preservingSettledPage
    ? settledPagination.index
    : pageIndex;
  const displayedPageSize = preservingSettledPage
    ? settledPagination.size
    : pageSize;

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
    (searchCondition: Condition, hasFilters: boolean) => {
      setQueryTransition("replace");
      setSearchCondition(searchCondition);
      setHasSearchFilters(hasFilters);
      clearSelection();
      updateQuery(
        pagedQuery({
          condition: and(
            RetryConditions.categoryToCondition(category),
            searchCondition,
          ),
          sort: [],
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
          RetryConditions.categoryToCondition(category),
          searchCondition,
        ),
        sort: [],
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
        RetryConditions.categoryToCondition(category),
        searchCondition,
      ),
      sort: [],
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
      if (document.visibilityState === "visible") {
        refresh();
      }
    };
    const timer = window.setInterval(refreshVisibleQueue, 30_000);
    window.addEventListener("focus", refreshVisibleQueue);
    document.addEventListener("visibilitychange", refreshVisibleQueue);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", refreshVisibleQueue);
      document.removeEventListener("visibilitychange", refreshVisibleQueue);
    };
  }, [category, refresh]);

  const master = (
    <section
      className="flex h-full min-h-0 flex-col border-r bg-white"
      aria-label="Failed executions"
    >
      <FailedSearch
        key={searchResetToken}
        onSearch={onSearch}
        loading={transitioning}
      />
      <FailedTable
        error={visibleError}
        hasActiveFilters={hasSearchFilters}
        loading={transitioning}
        pagedList={page}
        pageIndex={displayedPageIndex}
        pageSize={displayedPageSize}
        selectedId={activeId}
        onPaginationChange={onPaginationChange}
        onClearFilters={clearFilters}
        onRetry={refresh}
        onSelect={select}
      />
    </section>
  );

  const details = suspendingSelection ? (
    <LoadingPageDetails />
  ) : selectedState ? (
    <FailedDetails state={selectedState} onChanged={refresh} />
  ) : selectedId ? (
    <FetchingFailedDetails
      key={selectedId}
      id={selectedId}
      onChanged={refresh}
    />
  ) : (
    <EmptyDetails />
  );

  if (!desktop) {
    return (
      <div className="h-full min-h-0">
        {master}
        <Sheet
          open={Boolean(selectedId)}
          onOpenChange={(open) => {
            if (!open) {
              clearSelection();
            }
          }}
        >
          <SheetContent
            className="w-full gap-0 p-0 sm:max-w-none"
            style={{ width: "100%", maxWidth: "none" }}
            showCloseButton
            onOpenAutoFocus={(event) => {
              event.preventDefault();
              mobileDetailsFocusRef.current?.focus();
            }}
          >
            <SheetHeader className="sr-only">
              <SheetTitle>Execution failed details</SheetTitle>
              <SheetDescription>
                Inspect context and prepare compensation.
              </SheetDescription>
            </SheetHeader>
            <div
              ref={mobileDetailsFocusRef}
              tabIndex={-1}
              aria-label="Execution details panel"
              className="min-h-0 flex-1 overflow-hidden outline-none"
            >
              {details}
            </div>
          </SheetContent>
        </Sheet>
      </div>
    );
  }

  return (
    <ResizablePanelGroup
      id="failed-executions-layout"
      orientation="horizontal"
      className="h-full min-h-0"
      defaultLayout={splitLayout}
      onLayoutChanged={(layout, meta) => {
        if (meta.isUserInteraction) {
          saveSplitLayout(layout);
        }
      }}
    >
      <ResizablePanel id="execution-list" minSize="30" maxSize="52">
        {master}
      </ResizablePanel>
      <ResizableHandle
        withHandle
        aria-label="Resize execution list and details"
        className="z-20 bg-slate-200"
      />
      <ResizablePanel id="execution-details" minSize="48">
        <div className="h-full min-h-0">{details}</div>
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
