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

import type { ExecutionFailedState } from "../../../generated";
import { CopyButton } from "@/components/CopyButton";
import { Actions } from "../Actions.tsx";
import { StatusBadge } from "../StatusBadge.tsx";
import type { OnChangedCapable } from "../types.ts";

export interface FailedDetailsHeaderProps extends OnChangedCapable {
  state: ExecutionFailedState;
  mutationsDisabled?: boolean;
}

export function FailedDetailsHeader({
  state,
  onChanged,
  mutationsDisabled,
}: FailedDetailsHeaderProps) {
  return (
    <header className="flex flex-col items-stretch gap-4 border-b bg-white py-4 pr-14 pl-5 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:pr-6 sm:pl-6">
      <div className="flex min-w-0 items-center gap-3">
        <span className="h-9 w-1 shrink-0 rounded-full bg-red-500" aria-hidden />
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
      <Actions
        state={state}
        disabled={mutationsDisabled}
        onChanged={onChanged}
      />
    </header>
  );
}
