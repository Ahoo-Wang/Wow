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

import { Pencil, RotateCcw } from "lucide-react";
import type { ExecutionFailedState } from "../../../generated";
import { useGlobalDrawer } from "@/components/GlobalDrawer";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/utils/dates";
import { formatSeconds } from "@/utils/durations.ts";
import { ApplyRetrySpec } from "../ApplyRetrySpec.tsx";
import { MarkRecoverable } from "../MarkRecoverable.tsx";
import { BooleanBadge } from "../StatusBadge.tsx";
import type { OnChangedCapable } from "../types.ts";
import { useI18n } from "@/i18n.tsx";

type RecoveryState = Pick<
  ExecutionFailedState,
  "id" | "isRetryable" | "recoverable" | "retrySpec" | "retryState"
>;

export interface RecoveryStatusProps extends OnChangedCapable {
  state: RecoveryState;
  mutationsDisabled?: boolean;
}

export function RecoveryStatus({
  state,
  onChanged,
  mutationsDisabled,
}: RecoveryStatusProps) {
  const { openDrawer } = useGlobalDrawer();
  const { locale, t } = useI18n();

  const editRetry = () =>
    openDrawer({
      title: t("Apply retry specification"),
      description: t("Tune retry limits and timing for this execution."),
      width: 440,
      children: (
        <ApplyRetrySpec
          id={state.id}
          retrySpec={state.retrySpec}
          onChanged={onChanged}
        />
      ),
    });

  return (
    <section
      className="overflow-hidden rounded-lg border border-blue-200 bg-white shadow-sm"
      aria-label={t("Recovery status")}
    >
      <div className="flex min-h-14 items-center justify-between gap-3 border-b bg-blue-50/60 px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <RotateCcw className="size-4 shrink-0 text-blue-600" />
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-slate-900">
              {t("Recovery status")}
            </h2>
            <p className="text-xs text-slate-500">
              {t("Retry eligibility and timing")}
            </p>
          </div>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={t("Edit retry specification")}
          disabled={mutationsDisabled}
          onClick={editRetry}
        >
          <Pencil />
        </Button>
      </div>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-4 p-4 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
        <div>
          <dt className="text-xs text-slate-500">{t("Retry progress")}</dt>
          <dd className="mt-1 text-sm tabular-nums text-slate-800">
            <span className="font-semibold">
              {t("{current} of {total}", {
                current: state.retryState.retries,
                total: state.retrySpec.maxRetries,
              })}
            </span>
            <span className="mt-0.5 block text-xs text-slate-500">
              {t("Last: {date}", {
                date: formatDate(state.retryState.retryAt, undefined, locale),
              })}
            </span>
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">{t("Next retry at")}</dt>
          <dd className="mt-1 text-sm tabular-nums text-slate-800">
            {formatDate(state.retryState.nextRetryAt, undefined, locale)}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">{t("Recoverable")}</dt>
          <dd className="mt-1">
            <MarkRecoverable
              id={state.id}
              recoverable={state.recoverable}
              disabled={mutationsDisabled}
              onChanged={onChanged}
            />
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">{t("Retryable")}</dt>
          <dd className="mt-1">
            <BooleanBadge value={state.isRetryable} />
          </dd>
        </div>
      </dl>
      <dl className="grid grid-cols-2 gap-4 border-t bg-slate-50/70 px-4 py-3">
        <div>
          <dt className="text-[11px] text-slate-500">{t("Min backoff")}</dt>
          <dd className="mt-0.5 text-xs tabular-nums text-slate-700">
            {formatSeconds(state.retrySpec.minBackoff, locale)} (
            {state.retrySpec.minBackoff.toLocaleString()} s)
          </dd>
        </div>
        <div>
          <dt className="text-[11px] text-slate-500">{t("Timeout")}</dt>
          <dd className="mt-0.5 text-xs tabular-nums text-slate-700">
            {formatSeconds(state.retrySpec.executionTimeout, locale)} (
            {state.retrySpec.executionTimeout.toLocaleString()} s)
          </dd>
        </div>
      </dl>
    </section>
  );
}
