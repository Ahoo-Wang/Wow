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
  type AggregateId,
  type BindingError,
  type ErrorInfo,
  type FunctionInfo,
  type Identifier,
  type RecoverableType,
  SnapshotMetadataFields,
  type Version,
} from "@ahoo-wang/fetcher-wow";

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

export class ExecutionFailedFields {
  static readonly STATUS = `${SnapshotMetadataFields.STATE}.status`;
  static readonly RECOVERABLE = `${SnapshotMetadataFields.STATE}.recoverable`;
  static readonly IS_RETRYABLE = `${SnapshotMetadataFields.STATE}.isRetryable`;
  static readonly IS_BELOW_RETRY_THRESHOLD = `${SnapshotMetadataFields.STATE}.isBelowRetryThreshold`;
  static readonly NEXT_RETRY_AT = `${SnapshotMetadataFields.STATE}.retryState.nextRetryAt`;
  static readonly TIMEOUT_AT = `${SnapshotMetadataFields.STATE}.retryState.timeoutAt`;
}

/**
 * compensation.execution_failed.ExecutionFailedStatus
 */
export enum ExecutionFailedStatus {
  FAILED = "FAILED",
  PREPARED = "PREPARED",
  SUCCEEDED = "SUCCEEDED",
}
