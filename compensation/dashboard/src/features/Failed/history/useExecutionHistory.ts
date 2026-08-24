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

import { usePagedQuery } from "@ahoo-wang/fetcher-react";
import type { DomainEventStream, PagedList } from "@ahoo-wang/fetcher-wow";
import {
  desc,
  DomainEventStreamMetadataFields,
  filter,
  pagedList,
  pagedQuery,
} from "@ahoo-wang/fetcher-wow";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ExecutionFailedDomainEventType } from "../../../generated";
import { executionFailedEventStreamQueryClient } from "../../../services";

export const EXECUTION_HISTORY_PAGE_SIZE = 10;

export type ExecutionEventStream =
  DomainEventStream<ExecutionFailedDomainEventType>;

interface UseExecutionHistoryOptions {
  enabled: boolean;
  executionId: string;
  refreshToken: number;
}

function createExecutionHistoryQuery(executionId: string, pageIndex: number) {
  return pagedQuery({
    filter: filter.aggregateId(executionId),
    sort: [desc(DomainEventStreamMetadataFields.VERSION)],
    pagination: { index: pageIndex, size: EXECUTION_HISTORY_PAGE_SIZE },
  });
}

function isAbortError(error: Error): boolean {
  return (
    error.name === "AbortError" ||
    error.message.toLowerCase().includes("signal is aborted")
  );
}

export function useExecutionHistory({
  enabled,
  executionId,
  refreshToken,
}: UseExecutionHistoryOptions) {
  const [page, setPage] = useState<PagedList<ExecutionEventStream>>(() =>
    pagedList<ExecutionEventStream>(),
  );
  const [settledPageIndex, setSettledPageIndex] = useState(1);
  const requestedPageIndex = useRef(1);
  const observedExecutionId = useRef(executionId);
  const observedRefreshToken = useRef(refreshToken);
  const wasEnabled = useRef(false);

  const { abort, loading, error, execute, setQuery } = usePagedQuery<
    ExecutionEventStream,
    string,
    Error
  >({
    autoExecute: false,
    initialQuery: createExecutionHistoryQuery(executionId, 1),
    execute: (query, attributes, abortController) =>
      executionFailedEventStreamQueryClient.paged<ExecutionEventStream>(
        query,
        attributes,
        abortController,
      ),
    onSuccess: (nextPage) => {
      setPage(nextPage);
      setSettledPageIndex(requestedPageIndex.current);
    },
  });

  const loadPage = useCallback(
    (pageIndex: number) => {
      requestedPageIndex.current = pageIndex;
      setQuery(createExecutionHistoryQuery(executionId, pageIndex));
      void execute();
    },
    [execute, executionId, setQuery],
  );

  const retry = useCallback(() => {
    void execute();
  }, [execute]);

  useEffect(() => {
    if (!enabled) {
      if (wasEnabled.current) {
        abort();
      }
      wasEnabled.current = false;
      return;
    }

    const executionChanged = observedExecutionId.current !== executionId;
    const refreshRequested = observedRefreshToken.current !== refreshToken;
    const expanding = !wasEnabled.current;
    wasEnabled.current = true;
    observedExecutionId.current = executionId;
    observedRefreshToken.current = refreshToken;

    if (!expanding && !executionChanged && !refreshRequested) {
      return;
    }

    if (executionChanged) {
      setPage(pagedList<ExecutionEventStream>());
      setSettledPageIndex(1);
    }
    loadPage(executionChanged || refreshRequested ? 1 : settledPageIndex);
  }, [abort, enabled, executionId, loadPage, refreshToken, settledPageIndex]);

  return {
    error: error && !isAbortError(error) ? error : undefined,
    loadPage,
    loading,
    page,
    retry,
    settledPageIndex,
  };
}
