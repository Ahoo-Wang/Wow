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

import { Pencil, Workflow } from "lucide-react";
import type { ReactNode } from "react";
import type { ExecutionFailedState } from "../../../generated";
import { CopyButton } from "@/components/CopyButton";
import { useGlobalDrawer } from "@/components/GlobalDrawer";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { ChangeFunction } from "../ChangeFunction.tsx";
import type { OnChangedCapable } from "../types.ts";
import { useI18n } from "@/i18n.tsx";

type ExecutionContextState = Pick<
  ExecutionFailedState,
  "eventId" | "function" | "id"
>;

export interface ExecutionContextProps extends OnChangedCapable {
  state: ExecutionContextState;
  mutationsDisabled?: boolean;
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

export function ExecutionContext({
  state,
  onChanged,
  mutationsDisabled,
}: ExecutionContextProps) {
  const { t } = useI18n();
  const { openDrawer } = useGlobalDrawer();
  const aggregate = state.eventId.aggregateId;

  const editFunction = () =>
    openDrawer({
      title: t("Change function"),
      description: t("Update the handler identity for this failed execution."),
      width: 500,
      children: (
        <ChangeFunction
          id={state.id}
          functionInfo={state.function}
          onChanged={onChanged}
        />
      ),
    });

  return (
    <section
      className="overflow-hidden rounded-lg border bg-white shadow-sm"
      aria-label={t("Execution context")}
    >
      <div className="flex min-h-14 items-center gap-3 border-b bg-slate-50/70 px-4 py-3">
        <Workflow className="size-4 shrink-0 text-slate-500" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-slate-900">
            {t("Execution context")}
          </h2>
          <p className="text-xs text-slate-500">
            {t("Handler and source event identifiers")}
          </p>
        </div>
      </div>
      <dl className="grid grid-cols-1 gap-x-6 p-3 xl:grid-cols-2">
        <div className="space-y-1 xl:border-r xl:pr-6">
          <DetailRow
            label={t("Processor")}
            action={
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                aria-label={t("Edit function")}
                disabled={mutationsDisabled}
                onClick={editFunction}
              >
                <Pencil />
              </Button>
            }
          >
            <span className="block truncate" title={state.function.processorName}>
              {state.function.processorName}
            </span>
          </DetailRow>
          <DetailRow label={t("Function kind")}>
            {state.function.contextName} / {state.function.functionKind}
          </DetailRow>
          <DetailRow label={t("Aggregate")}>
            {aggregate.contextName} / {aggregate.aggregateName}
          </DetailRow>
          <DetailRow label={t("Tenant")}>
            <span className="truncate">{aggregate.tenantId}</span>
          </DetailRow>
        </div>

        <div className="mt-4 space-y-1 border-t pt-4 xl:mt-0 xl:border-t-0 xl:pl-6 xl:pt-0">
          <DetailRow label={t("Event version")}>{state.eventId.version}</DetailRow>
          <DetailRow
            label={t("Event ID")}
            action={<CopyButton value={state.eventId.id} label="event ID" />}
          >
            <span className="block truncate font-mono text-xs">
              {state.eventId.id}
            </span>
          </DetailRow>
          <DetailRow
            label={t("Aggregate ID")}
            action={
              <CopyButton value={aggregate.aggregateId} label="aggregate ID" />
            }
          >
            <span className="block truncate font-mono text-xs">
              {aggregate.aggregateId}
            </span>
          </DetailRow>
        </div>
      </dl>
    </section>
  );
}
