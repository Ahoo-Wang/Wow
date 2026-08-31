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

import { AlertTriangle } from "lucide-react";
import type { ExecutionFailedState } from "../../../generated";
import { ExecutionFailedStatus } from "../../../generated";
import { cn } from "@/lib/utils";
import { formatDate } from "@/utils/dates";
import { useI18n } from "@/i18n.tsx";

export interface FailureSummaryProps {
  state: Pick<ExecutionFailedState, "error" | "executeAt" | "status">;
}

export function FailureSummary({ state }: FailureSummaryProps) {
  const historical = state.status === ExecutionFailedStatus.SUCCEEDED;
  const { locale, t } = useI18n();

  return (
    <section
      className={cn(
        "overflow-hidden rounded-lg border bg-white shadow-sm",
        historical ? "border-slate-200" : "border-red-200",
      )}
      aria-label={historical ? t("Last failure summary") : t("Failure summary")}
    >
      <div
        className={cn(
          "flex min-h-14 items-center justify-between gap-3 border-b px-4 py-3",
          historical ? "bg-slate-50" : "bg-red-50/70",
        )}
      >
        <div className="flex min-w-0 items-center gap-3">
          <AlertTriangle
            className={cn(
              "size-4 shrink-0",
              historical ? "text-slate-500" : "text-red-600",
            )}
          />
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-slate-900">
              {historical ? t("Last failure") : t("Failure summary")}
            </h2>
            <p className="text-xs text-slate-500">
              {historical
                ? t("Most recent recorded failure")
                : t("Current processing failure")}
            </p>
          </div>
        </div>
        <span
          className="max-w-44 truncate rounded-md border border-red-200 bg-white px-2 py-1 font-mono text-[11px] font-medium text-red-700"
          title={state.error.errorCode}
        >
          {state.error.errorCode}
        </span>
      </div>
      <div className="p-4">
        <p
          className="line-clamp-3 text-sm leading-6 font-medium text-slate-800"
          title={state.error.errorMsg}
        >
          {state.error.errorMsg}
        </p>
        <dl className="mt-4 border-t pt-3">
          <div>
            <dt className="text-xs text-slate-500">
              {historical ? t("Succeeded at") : t("Failed at")}
            </dt>
            <dd className="mt-1 text-sm tabular-nums text-slate-800">
              {formatDate(state.executeAt, undefined, locale)}
            </dd>
          </div>
        </dl>
      </div>
    </section>
  );
}
