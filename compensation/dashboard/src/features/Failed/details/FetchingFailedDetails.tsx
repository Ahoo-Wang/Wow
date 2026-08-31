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

import type { ExecutionFailedAggregatedFields } from "../../../generated";
import { type ExecutionFailedState } from "../../../generated";
import { filter, singleQuery } from "@ahoo-wang/fetcher-wow";
import { FailedDetails } from "./FailedDetails.tsx";
import { queryExecutionFailedState } from "../../../services";
import { useSingleQuery } from "@ahoo-wang/fetcher-react";
import type { FetcherError } from "@ahoo-wang/fetcher";
import { useCallback, useMemo } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { OnChangedCapable } from "../types.ts";
import { useI18n } from "@/i18n.tsx";

export interface FetchingFailedDetailsProps extends OnChangedCapable {
  id: string;
  mutationsDisabled?: boolean;
}

export function FetchingFailedDetails({
  id,
  onChanged,
  mutationsDisabled,
}: FetchingFailedDetailsProps) {
  const { t } = useI18n();
  const query = useMemo(
    () =>
      singleQuery<ExecutionFailedAggregatedFields>({
        filter: filter.aggregateId(id),
      }),
    [id],
  );
  const {
    result,
    error,
    loading,
    execute: refreshDetails,
  } = useSingleQuery<
    ExecutionFailedState | null,
    ExecutionFailedAggregatedFields,
    FetcherError
  >({
    query,
    execute: queryExecutionFailedState,
  });
  const refreshDetailsAndList = useCallback(() => {
    onChanged?.();
    void refreshDetails();
  }, [onChanged, refreshDetails]);

  if (loading || (result && result.id !== id)) {
    return (
      <div
        role="status"
        aria-label={t("Loading execution details")}
        className="h-full space-y-4 bg-slate-50 p-5"
      >
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-[420px] w-full" />
      </div>
    );
  }

  if (error) {
    return (
      <div
        role="alert"
        className="flex h-full min-h-60 items-center justify-center bg-slate-50 p-6 text-center"
      >
        <div>
          <p className="text-sm font-medium text-red-600">
            {t("Failed to load execution")}
          </p>
          <p className="mt-1 text-xs text-slate-500">{error.message}</p>
          <Button
            type="button"
            variant="outline"
            className="mt-4"
            onClick={() => void refreshDetails()}
          >
            <RefreshCw />
            {t("Retry")}
          </Button>
        </div>
      </div>
    );
  }

  if (result === undefined) {
    return (
      <div
        role="status"
        aria-label={t("Loading execution details")}
        className="h-full space-y-4 bg-slate-50 p-5"
      >
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-[420px] w-full" />
      </div>
    );
  }

  if (result === null) {
    return (
      <div className="flex h-full min-h-60 items-center justify-center bg-slate-50 p-6 text-center">
        <div>
          <p className="text-sm font-medium text-slate-700">
            {t("Execution not found")}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            {t("No compensation execution matches {id}.", { id })}
          </p>
        </div>
      </div>
    );
  }

  return (
    <FailedDetails
      state={result}
      mutationsDisabled={mutationsDisabled}
      onChanged={refreshDetailsAndList}
    />
  );
}
