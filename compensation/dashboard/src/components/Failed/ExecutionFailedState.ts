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

import type {
  BindingError,
  ErrorInfo,
  RecoverableType,
} from "../../client/types/error.ts";
import type { AggregateId } from "../../client/types/modeling.ts";
import type { FunctionInfo } from "../../client/types/function.ts";
import type { Identifier, Version } from "../../client/types/common.ts";

/**
 * compensation.ExecutionFailedState
 */
export interface ExecutionFailedState extends Identifier {
  error?: ErrorDetails;
  eventId?: EventId;
  executeAt?: number;
  function?: FunctionInfo;
  isBelowRetryThreshold?: boolean;
  isRetryable?: boolean;
  recoverable?: RecoverableType;
  retrySpec?: RetrySpec;
  retryState?: RetryState;
  status?: ExecutionFailedStatus;
}

/**
 * compensation.execution_failed.ErrorDetails
 */
export interface ErrorDetails extends ErrorInfo {
  bindingErrors?: BindingError[];
  stackTrace: string;
}

export interface EventId extends Identifier, Version {
  aggregateId: AggregateId;
}

/**
 * compensation.execution_failed.RetrySpec
 */
export interface RetrySpec {
  executionTimeout: number;
  maxRetries: number;
  minBackoff: number;
}

/**
 * compensation.execution_failed.RetryState
 */
export interface RetryState {
  nextRetryAt: number;
  retries: number;
  retryAt: number;
  timeoutAt: number;
}

/**
 * compensation.execution_failed.ExecutionFailedStatus
 */
export type ExecutionFailedStatus = "FAILED" | "PREPARED" | "SUCCEEDED";
