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

/**
 * compensation.IExecutionFailedState
 */
export interface ExecutionFailedState {
  id: string;
  eventId: EventId;
  error: ErrorDetails;
  executionTime: number;
  functionKind: FunctionKind;
  isRetryable: boolean;
  processor: ProcessorInfoData;
  retrySpec: RetrySpec;
  retryState: RetryState;
  status: Status;
}

/**
 * compensation.ErrorDetails
 */
export interface ErrorDetails {
  errorCode: string;
  errorMsg: string;
  stackTrace: string;
  succeeded: boolean;
}

/**
 * compensation.EventId
 */
export interface EventId {
  aggregateId: AggregateId;
  id: string;
  version: number;
}

/**
 * wow.AggregateId
 */
export interface AggregateId {
  aggregateId: string;
  aggregateName: string;
  contextName: string;
  tenantId: string;
}

export enum FunctionKind {
  Command = "COMMAND",
  Error = "ERROR",
  Event = "EVENT",
  Sourcing = "SOURCING",
  StateEvent = "STATE_EVENT",
}

/**
 * wow.ProcessorInfoData
 */
export interface ProcessorInfoData {
  contextName: string;
  processorName: string;
}

/**
 * compensation.RetrySpec
 */
export interface RetrySpec {
  executionTimeout: number;
  maxRetries: number;
  minBackoff: number;
}

/**
 * compensation.RetryState
 */
export interface RetryState {
  nextRetryAt: number;
  retries: number;
  retryAt: number;
  timoutAt: number;
}

export enum Status {
  Failed = "FAILED",
  Prepared = "PREPARED",
  Succeeded = "SUCCEEDED",
}
