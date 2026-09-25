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

import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../generated";
import type { Message } from "@/i18n.tsx";

export interface CompensationCapabilities {
  canForcePrepare: boolean;
  canPrepare: boolean;
  unavailableReason?: Message;
}

/**
 * What of an execution's state decides which commands it takes — the same
 * rule as the command side's `canRetry()` / `canForceRetry()`. A whole
 * snapshot passes, and so does a workbench row, which holds these fields
 * alone (`record.rowFields`).
 */
export type OperableState = Pick<
  ExecutionFailedState,
  "status" | "isBelowRetryThreshold"
> & { retryState: Pick<ExecutionFailedState["retryState"], "timeoutAt"> };

export function getCompensationCapabilities(
  state: OperableState,
  now = Date.now(),
): CompensationCapabilities {
  if (state.status === ExecutionFailedStatus.SUCCEEDED) {
    return {
      canForcePrepare: false,
      canPrepare: false,
      unavailableReason: "This execution has already succeeded.",
    };
  }

  if (
    state.status === ExecutionFailedStatus.PREPARED &&
    now <= state.retryState.timeoutAt
  ) {
    return {
      canForcePrepare: false,
      canPrepare: false,
      unavailableReason: "Execution is in progress; wait until it times out.",
    };
  }

  if (!state.isBelowRetryThreshold) {
    return {
      canForcePrepare: true,
      canPrepare: false,
      unavailableReason:
        "Retry limit reached; force prepare remains available.",
    };
  }

  return { canForcePrepare: true, canPrepare: true };
}
