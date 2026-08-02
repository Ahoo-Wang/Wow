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

import { Pencil } from "lucide-react";
import type { ReactNode } from "react";
import type { ExecutionFailedState } from "../../../generated";
import { ExecutionFailedStatus } from "../../../generated";
import { CopyButton } from "@/components/CopyButton";
import { useGlobalDrawer } from "@/components/GlobalDrawer";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { formatDate } from "@/utils/dates";
import { Actions } from "../Actions.tsx";
import { ApplyRetrySpec } from "../ApplyRetrySpec.tsx";
import { ChangeFunction } from "../ChangeFunction.tsx";
import { MarkRecoverable } from "../MarkRecoverable.tsx";
import { BooleanBadge, StatusBadge } from "../StatusBadge.tsx";
import { ErrorDetails } from "./ErrorDetails.tsx";
import type { OnChangedCapable } from "../types.ts";

export interface FailedDetailsProps extends OnChangedCapable {
  state: ExecutionFailedState;
}

interface DetailRowProps {
  label: string;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}

function DetailRow({ label, children, action, className }: DetailRowProps) {
  return (
    <div
      className={cn(
        "grid min-h-9 grid-cols-[112px_minmax(0,1fr)_auto] items-center gap-3",
        className,
      )}
    >
      <dt className="text-sm text-slate-500">{label}</dt>
      <dd className="min-w-0 break-words text-sm text-slate-800">{children}</dd>
      {action ? <div className="flex items-center">{action}</div> : null}
    </div>
  );
}

export function FailedDetails({ state, onChanged }: FailedDetailsProps) {
  const { openDrawer } = useGlobalDrawer();
  const aggregate = state.eventId.aggregateId;

  const editFunction = () =>
    openDrawer({
      title: "Change function",
      description: "Update the handler identity for this failed execution.",
      width: 500,
      children: (
        <ChangeFunction
          id={state.id}
          functionInfo={state.function}
          onChanged={onChanged}
        />
      ),
    });

  const editRetry = () =>
    openDrawer({
      title: "Apply retry specification",
      description: "Tune retry limits and timing for this execution.",
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
    <article className="flex h-full min-h-0 flex-col bg-slate-50/50">
      <header className="flex flex-col items-stretch gap-4 border-b bg-white py-4 pr-14 pl-5 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:pr-6 sm:pl-6">
        <div className="flex min-w-0 items-center gap-3">
          <span
            className="h-9 w-1 shrink-0 rounded-full bg-red-500"
            aria-hidden
          />
          <div className="min-w-0">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <h1 className="min-w-0 truncate text-xl font-semibold tracking-tight text-slate-950">
                {state.function.name}
              </h1>
              <StatusBadge status={state.status} />
            </div>
            <div className="mt-1 flex items-center gap-1 text-xs text-slate-500">
              <span className="truncate">{state.id}</span>
              <CopyButton value={state.id} label="execution ID" />
            </div>
          </div>
        </div>
        <Actions state={state} onChanged={onChanged} />
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
        <section
          className="rounded-lg border bg-white p-3 shadow-sm"
          aria-label="Execution details"
        >
          <dl className="grid grid-cols-1 gap-x-6 xl:grid-cols-2">
            <div className="space-y-1 xl:border-r xl:pr-6">
              <DetailRow label="Function kind">
                {state.function.contextName} / {state.function.functionKind}
              </DetailRow>
              <DetailRow
                label="Processor"
                action={
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    aria-label="Edit function"
                    onClick={editFunction}
                  >
                    <Pencil />
                  </Button>
                }
              >
                <span
                  className="block truncate"
                  title={state.function.processorName}
                >
                  {state.function.processorName}
                </span>
              </DetailRow>
              <DetailRow label="Aggregate">
                {aggregate.contextName} / {aggregate.aggregateName}
              </DetailRow>
              <DetailRow label="Event version">
                {state.eventId.version}
              </DetailRow>
              <DetailRow
                label="Event ID"
                action={
                  <CopyButton value={state.eventId.id} label="event ID" />
                }
              >
                <span className="block truncate font-mono text-xs">
                  {state.eventId.id}
                </span>
              </DetailRow>
              <DetailRow
                label="Aggregate ID"
                action={
                  <CopyButton
                    value={aggregate.aggregateId}
                    label="aggregate ID"
                  />
                }
              >
                <span className="block truncate font-mono text-xs">
                  {aggregate.aggregateId}
                </span>
              </DetailRow>
              <DetailRow label="Tenant">
                <span className="truncate">{aggregate.tenantId}</span>
              </DetailRow>
            </div>

            <div className="mt-4 space-y-1 border-t pt-4 xl:mt-0 xl:border-t-0 xl:pl-6 xl:pt-0">
              <DetailRow label="Execute at">
                {formatDate(state.executeAt)}
              </DetailRow>
              <DetailRow
                label="Retry"
                action={
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    aria-label="Edit retry specification"
                    onClick={editRetry}
                  >
                    <Pencil />
                  </Button>
                }
              >
                <span className="flex flex-wrap items-baseline gap-x-2 tabular-nums">
                  <span>
                    {state.retryState.retries} of {state.retrySpec.maxRetries}
                  </span>
                  <span className="text-xs text-slate-500">
                    Last: {formatDate(state.retryState.retryAt)}
                  </span>
                </span>
              </DetailRow>
              <DetailRow label="Next retry at">
                {formatDate(state.retryState.nextRetryAt)}
              </DetailRow>
              <DetailRow label="Recoverable">
                <MarkRecoverable
                  id={state.id}
                  recoverable={state.recoverable}
                  onChanged={onChanged}
                />
              </DetailRow>
              <DetailRow label="Retryable">
                <BooleanBadge value={state.isRetryable} />
              </DetailRow>
              <DetailRow label="Min backoff">
                <span className="tabular-nums">
                  {state.retrySpec.minBackoff.toLocaleString()} ms
                </span>
              </DetailRow>
              <DetailRow label="Timeout">
                <span className="tabular-nums">
                  {state.retrySpec.executionTimeout.toLocaleString()} ms
                </span>
              </DetailRow>
            </div>
          </dl>
        </section>

        <ErrorDetails
          key={`${state.id}-${state.status}`}
          error={state.error}
          historical={state.status === ExecutionFailedStatus.SUCCEEDED}
        />
      </div>
    </article>
  );
}
