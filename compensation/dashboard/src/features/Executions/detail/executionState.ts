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

import type { FunctionKind, RecoverableType } from "@ahoo-wang/wow-client";
import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import type { ApplyRetrySpec } from "@/generated";
import type { Message } from "@/i18n.tsx";

/** The execution's state, as the whole record carries it. */
export interface ExecutionState {
  status?: "FAILED" | "PREPARED" | "SUCCEEDED";
  recoverable?: RecoverableType;
  isRetryable?: boolean;
  isBelowRetryThreshold?: boolean;
  executeAt?: number;
  retrySpec?: ApplyRetrySpec;
  retryState?: {
    retries?: number;
    retryAt?: number;
    timeoutAt?: number;
    nextRetryAt?: number;
  };
  function?: {
    contextName: string;
    processorName: string;
    name: string;
    functionKind: FunctionKind;
  };
  eventId?: {
    id?: string;
    version?: number;
    aggregateId?: {
      contextName?: string;
      aggregateName?: string;
      aggregateId?: string;
      tenantId?: string;
    };
  };
  error?: { errorCode?: string; errorMsg?: string; stackTrace?: string };
}

export function stateOf(row: RecordRow): ExecutionState {
  const state = row.data.state;
  return state && typeof state === "object" ? (state as ExecutionState) : {};
}

/** The execution's name: the handler that failed, 「Saga.onEvent」. */
export function executionTitle(row: RecordRow): string | undefined {
  const target = stateOf(row).function;
  return target ? `${target.processorName}.${target.name}` : undefined;
}

const RECOVERABILITY_LABEL: Partial<Record<RecoverableType, Message>> = {
  RECOVERABLE: "Recoverable",
  UNRECOVERABLE: "Unrecoverable",
  UNKNOWN: "Unknown",
};

/** How an execution's recoverability reads. */
export function recoverabilityLabel(
  value: RecoverableType | undefined,
): Message {
  return (value && RECOVERABILITY_LABEL[value]) ?? "Unknown";
}
