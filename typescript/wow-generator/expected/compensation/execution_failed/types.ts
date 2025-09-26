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
  RecoverableType,
  FunctionKind,
  FunctionInfo,
  BindingError,
  AggregateId,
  Condition,
  DomainEventStream,
  ListQuery,
  PagedList,
  PagedQuery,
  SingleQuery,
  MaterializedSnapshot,
  StateEvent,
} from '@ahoo-wang/fetcher-wow';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

export interface ApplyExecutionFailed {
  error: ErrorDetails;
  executeAt: number;
  recoverable: RecoverableType;
}

export interface ApplyExecutionSuccess {
  executeAt: number;
}

export interface ApplyRetrySpec {
  executionTimeout: number;
  maxRetries: number;
  minBackoff: number;
}

export interface ChangeFunction {
  contextName: string;
  functionKind: FunctionKind;
  name: string;
  processorName: string;
}

export interface CompensationPrepared {
  eventId: EventId;
  function: FunctionInfo;
  retryState: RetryState;
}

export interface CreateExecutionFailed {
  error: ErrorDetails;
  eventId: EventId;
  executeAt: number;
  function: FunctionInfo;
  recoverable: RecoverableType;
  retrySpec: null | RetrySpec;
}

export interface ErrorDetails {
  bindingErrors: BindingError[];
  errorCode: string;
  errorMsg: string;
  stackTrace: string;
  succeeded: boolean;
}

export interface EventId {
  aggregateId: AggregateId;
  id: string;
  version: number;
}

export interface ExecutionFailedAggregatedCondition
  extends Condition<ExecutionFailedAggregatedFields> {
}

export type ExecutionFailedAggregatedDomainEventTypes =
  | CompensationPrepared
  | ExecutionFailedApplied
  | ExecutionFailedCreated
  | ExecutionSuccessApplied
  | FunctionChanged
  | RecoverableMarked
  | RetrySpecApplied;

export interface ExecutionFailedAggregatedDomainEventStream
  extends DomainEventStream<ExecutionFailedAggregatedDomainEventTypes> {
}

export interface ExecutionFailedAggregatedDomainEventStreamPagedList
  extends PagedList<ExecutionFailedAggregatedDomainEventStream> {
}

export interface ExecutionFailedAggregatedDomainEventStreamServerSentEventNonNullData
  extends JsonServerSentEvent<ExecutionFailedAggregatedDomainEventStream> {
}

export enum ExecutionFailedAggregatedFields {
  EMPTY = '',
  AGGREGATE_ID = 'aggregateId',
  TENANT_ID = 'tenantId',
  OWNER_ID = 'ownerId',
  VERSION = 'version',
  EVENT_ID = 'eventId',
  FIRST_OPERATOR = 'firstOperator',
  OPERATOR = 'operator',
  FIRST_EVENT_TIME = 'firstEventTime',
  EVENT_TIME = 'eventTime',
  DELETED = 'deleted',
  STATE = 'state',
  STATE_ERROR = 'state.error',
  STATE_ERROR_BINDING_ERRORS = 'state.error.bindingErrors',
  STATE_ERROR_BINDING_ERRORS_MSG = 'state.error.bindingErrors.msg',
  STATE_ERROR_BINDING_ERRORS_NAME = 'state.error.bindingErrors.name',
  STATE_ERROR_ERROR_CODE = 'state.error.errorCode',
  STATE_ERROR_ERROR_MSG = 'state.error.errorMsg',
  STATE_ERROR_STACK_TRACE = 'state.error.stackTrace',
  STATE_ERROR_SUCCEEDED = 'state.error.succeeded',
  STATE_EVENT_ID = 'state.eventId',
  STATE_EVENT_ID_AGGREGATE_ID = 'state.eventId.aggregateId',
  STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE = 'state.eventId.aggregateId.namedAggregate',
  STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE_AGGREGATE_NAME = 'state.eventId.aggregateId.namedAggregate.aggregateName',
  STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE_CONTEXT_NAME = 'state.eventId.aggregateId.namedAggregate.contextName',
  STATE_EVENT_ID_AGGREGATE_ID_ID = 'state.eventId.aggregateId.id',
  STATE_EVENT_ID_AGGREGATE_ID_TENANT_ID = 'state.eventId.aggregateId.tenantId',
  STATE_EVENT_ID_ID = 'state.eventId.id',
  STATE_EVENT_ID_VERSION = 'state.eventId.version',
  STATE_EXECUTE_AT = 'state.executeAt',
  STATE_FUNCTION = 'state.function',
  STATE_FUNCTION_CONTEXT_NAME = 'state.function.contextName',
  STATE_FUNCTION_FUNCTION_KIND = 'state.function.functionKind',
  STATE_FUNCTION_NAME = 'state.function.name',
  STATE_FUNCTION_PROCESSOR_NAME = 'state.function.processorName',
  STATE_ID = 'state.id',
  STATE_RECOVERABLE = 'state.recoverable',
  STATE_RETRY_SPEC = 'state.retrySpec',
  STATE_RETRY_SPEC_EXECUTION_TIMEOUT = 'state.retrySpec.executionTimeout',
  STATE_RETRY_SPEC_MAX_RETRIES = 'state.retrySpec.maxRetries',
  STATE_RETRY_SPEC_MIN_BACKOFF = 'state.retrySpec.minBackoff',
  STATE_RETRY_STATE = 'state.retryState',
  STATE_RETRY_STATE_NEXT_RETRY_AT = 'state.retryState.nextRetryAt',
  STATE_RETRY_STATE_RETRIES = 'state.retryState.retries',
  STATE_RETRY_STATE_RETRY_AT = 'state.retryState.retryAt',
  STATE_RETRY_STATE_TIMEOUT_AT = 'state.retryState.timeoutAt',
  STATE_STATUS = 'state.status',
  STATE_IS_BELOW_RETRY_THRESHOLD = 'state.isBelowRetryThreshold',
  STATE_IS_RETRYABLE = 'state.isRetryable',
}

export interface ExecutionFailedAggregatedListQuery
  extends ListQuery<ExecutionFailedAggregatedFields> {
}

export interface ExecutionFailedAggregatedPagedQuery
  extends PagedQuery<ExecutionFailedAggregatedFields> {
}

export interface ExecutionFailedAggregatedSingleQuery
  extends SingleQuery<ExecutionFailedAggregatedFields> {
}

export interface ExecutionFailedApplied {
  error: ErrorDetails;
  executeAt: number;
  recoverable: RecoverableType;
}

export interface ExecutionFailedCreated {
  error: ErrorDetails;
  eventId: EventId;
  executeAt: number;
  function: FunctionInfo;
  recoverable: RecoverableType;
  retrySpec: RetrySpec;
  retryState: RetryState;
}

export interface ExecutionFailedState {
  error: ErrorDetails;
  eventId: EventId;
  executeAt: number;
  function: FunctionInfo;
  id: string;
  recoverable: RecoverableType;
  retrySpec: RetrySpec;
  retryState: RetryState;
  status: ExecutionFailedStatus;
  isBelowRetryThreshold: boolean;
  isRetryable: boolean;
}

export interface ExecutionFailedStateMaterializedSnapshot
  extends MaterializedSnapshot<ExecutionFailedState> {
}

export interface ExecutionFailedStateMaterializedSnapshotPagedList
  extends PagedList<ExecutionFailedStateMaterializedSnapshot> {
}

export interface ExecutionFailedStateMaterializedSnapshotServerSentEventNonNullData
  extends JsonServerSentEvent<ExecutionFailedStateMaterializedSnapshot> {
}

export interface ExecutionFailedStatePagedList
  extends PagedList<ExecutionFailedState> {
}

export interface ExecutionFailedStateServerSentEventNonNullData
  extends JsonServerSentEvent<ExecutionFailedState> {
}

export interface ExecutionFailedStateSnapshot
  extends MaterializedSnapshot<ExecutionFailedState> {
}

export interface ExecutionFailedStateStateEvent
  extends StateEvent<ExecutionFailedState> {
}

export enum ExecutionFailedStatus {
  FAILED = 'FAILED',
  PREPARED = 'PREPARED',
  SUCCEEDED = 'SUCCEEDED',
}

export interface ExecutionSuccessApplied {
  executeAt: number;
}

export interface ForcePrepareCompensation {
}

export interface FunctionChanged {
  contextName: string;
  functionKind: FunctionKind;
  name: string;
  processorName: string;
}

export interface MarkRecoverable {
  recoverable: RecoverableType;
}

export interface PrepareCompensation {
}

export interface RecoverableMarked {
  recoverable: RecoverableType;
}

export interface RetrySpec {
  executionTimeout: number;
  maxRetries: number;
  minBackoff: number;
}

export interface RetrySpecApplied {
  executionTimeout: number;
  maxRetries: number;
  minBackoff: number;
}

export interface RetryState {
  nextRetryAt: number;
  retries: number;
  retryAt: number;
  timeoutAt: number;
}
