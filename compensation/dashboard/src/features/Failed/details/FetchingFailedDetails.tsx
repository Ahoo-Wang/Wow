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
import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import type { OnChangedCapable } from "../types.ts";
import { useI18n } from "@/i18n.tsx";

export interface FetchingFailedDetailsProps extends OnChangedCapable {
  id: string;
  refreshToken?: number;
  mutationsDisabled?: boolean;
}

export function FetchingFailedDetails({
  id,
  refreshToken,
  onChanged,
  mutationsDisabled,
}: FetchingFailedDetailsProps) {
  const { t } = useI18n();
  const [lastSuccessfulState, setLastSuccessfulState] =
    useState<ExecutionFailedState | null>();
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
    autoExecute: false,
    execute: queryExecutionFailedState,
    onSuccess: setLastSuccessfulState,
  });
  useEffect(() => {
    void refreshDetails();
  }, [query, refreshDetails, refreshToken]);

  const refreshDetailsAndList = useCallback(() => {
    onChanged?.();
    if (!onChanged || refreshToken === undefined) void refreshDetails();
  }, [onChanged, refreshDetails, refreshToken]);

  const visibleState =
    result === undefined
      ? lastSuccessfulState?.id === id
        ? lastSuccessfulState
        : undefined
      : result;

  if ((loading && !visibleState) || (visibleState && visibleState.id !== id)) {
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

  if (error && !visibleState) {
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

  if (visibleState === undefined) {
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

  if (visibleState === null) {
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
    <div className="flex h-full min-h-0 flex-col">
      {error ? (
        <Alert className="shrink-0 rounded-none border-x-0 border-t-0">
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
              {t(
                "Execution refresh failed: {message}. Showing the last loaded state; changes are disabled.",
                { message: error.message },
              )}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={loading}
              onClick={() => void refreshDetails()}
            >
              <RefreshCw />
              {t("Retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      <div className="min-h-0 flex-1">
        <FailedDetails
          state={visibleState}
          mutationsDisabled={mutationsDisabled || loading || Boolean(error)}
          onChanged={refreshDetailsAndList}
        />
      </div>
    </div>
  );
}
