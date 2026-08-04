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
import { ExecutionFailedStatus } from "../../../generated";
import { useCallback, useState } from "react";
import { ExecutionHistory } from "../history/ExecutionHistory.tsx";
import type { OnChangedCapable } from "../types.ts";
import { ErrorDetails } from "./ErrorDetails.tsx";
import { ExecutionContext } from "./ExecutionContext.tsx";
import { FailedDetailsHeader } from "./FailedDetailsHeader.tsx";
import { FailureSummary } from "./FailureSummary.tsx";
import { RecoveryStatus } from "./RecoveryStatus.tsx";

export interface FailedDetailsProps extends OnChangedCapable {
  state: ExecutionFailedState;
  mutationsDisabled?: boolean;
}

export function FailedDetails({
  state,
  onChanged,
  mutationsDisabled,
}: FailedDetailsProps) {
  const historicalFailure = state.status === ExecutionFailedStatus.SUCCEEDED;
  const [historyRefreshToken, setHistoryRefreshToken] = useState(0);
  const handleChanged = useCallback(() => {
    setHistoryRefreshToken((current) => current + 1);
    onChanged?.();
  }, [onChanged]);

  return (
    <article className="flex h-full min-h-0 flex-col bg-slate-50/50">
      <FailedDetailsHeader
        state={state}
        mutationsDisabled={mutationsDisabled}
        onChanged={handleChanged}
      />

      <div className="grid min-h-0 flex-1 auto-rows-max gap-3 overflow-y-auto p-3 sm:p-4">
        <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
          <FailureSummary state={state} />
          <RecoveryStatus
            state={state}
            mutationsDisabled={mutationsDisabled}
            onChanged={handleChanged}
          />
        </div>

        <ExecutionHistory
          key={state.id}
          executionId={state.id}
          refreshToken={historyRefreshToken}
        />

        <ExecutionContext
          state={state}
          mutationsDisabled={mutationsDisabled}
          onChanged={handleChanged}
        />

        <ErrorDetails
          key={`${state.id}-${state.status}`}
          error={state.error}
          historical={historicalFailure}
          defaultExpanded={historicalFailure ? undefined : false}
        />
      </div>
    </article>
  );
}
