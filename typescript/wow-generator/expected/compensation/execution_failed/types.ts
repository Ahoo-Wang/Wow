import { AggregateId, BindingError, FunctionInfo, FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";

/**
 * apply_execution_failed
 * - key: compensation.execution_failed.ApplyExecutionFailed
 */
export interface ApplyExecutionFailed {
    error: ErrorDetails;
    /** - format: int64 */
    executeAt: number;
    recoverable: RecoverableType;
}

/**
 * apply_execution_success
 * - key: compensation.execution_failed.ApplyExecutionSuccess
 */
export interface ApplyExecutionSuccess {
    /** - format: int64 */
    executeAt: number;
}

/**
 * apply_retry_spec
 * - key: compensation.execution_failed.ApplyRetrySpec
 */
export interface ApplyRetrySpec {
    /** - format: int32 */
    executionTimeout: number;
    /** - format: int32 */
    maxRetries: number;
    /** - format: int32 */
    minBackoff: number;
}

/**
 * change_function
 * - key: compensation.execution_failed.ChangeFunction
 */
export interface ChangeFunction {
    contextName: string;
    functionKind: FunctionKind;
    name: string;
    processorName: string;
}

/**
 * compensation_prepared
 * - key: compensation.execution_failed.CompensationPrepared
 */
export interface CompensationPrepared {
    eventId: EventId;
    function: FunctionInfo;
    retryState: RetryState;
}

/**
 * create_execution_failed
 * - key: compensation.execution_failed.CreateExecutionFailed
 */
export interface CreateExecutionFailed {
    error: ErrorDetails;
    eventId: EventId;
    /** - format: int64 */
    executeAt: number;
    function: FunctionInfo;
    recoverable: RecoverableType;
    retrySpec: (null | RetrySpec);
}

/** - key: compensation.execution_failed.ErrorDetails */
export interface ErrorDetails {
    bindingErrors: BindingError[];
    errorCode: string;
    errorMsg: string;
    stackTrace: string;
    succeeded: boolean;
}

/** - key: compensation.execution_failed.EventId */
export interface EventId {
    aggregateId: AggregateId;
    id: string;
    /** - format: int32 */
    version: number;
}

/** - key: compensation.execution_failed.ExecutionFailedAggregatedFields */
export enum ExecutionFailedAggregatedFields {
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
    STATE_IS_RETRYABLE = 'state.isRetryable'
}

/**
 * execution_failed_applied
 * - key: compensation.execution_failed.ExecutionFailedApplied
 */
export interface ExecutionFailedApplied {
    error: ErrorDetails;
    /** - format: int64 */
    executeAt: number;
    recoverable: RecoverableType;
}

/**
 * execution_failed_created
 * - key: compensation.execution_failed.ExecutionFailedCreated
 */
export interface ExecutionFailedCreated {
    error: ErrorDetails;
    eventId: EventId;
    /** - format: int64 */
    executeAt: number;
    function: FunctionInfo;
    recoverable: RecoverableType;
    retrySpec: RetrySpec;
    retryState: RetryState;
}

/** - key: compensation.execution_failed.ExecutionFailedState */
export interface ExecutionFailedState {
    error: ErrorDetails;
    eventId: EventId;
    /** - format: int64 */
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

/** - key: compensation.execution_failed.ExecutionFailedStatus */
export enum ExecutionFailedStatus {
    FAILED = 'FAILED',
    PREPARED = 'PREPARED',
    SUCCEEDED = 'SUCCEEDED'
}

/**
 * execution_success_applied
 * - key: compensation.execution_failed.ExecutionSuccessApplied
 */
export interface ExecutionSuccessApplied {
    /** - format: int64 */
    executeAt: number;
}

/**
 * force_prepare_compensation
 * - key: compensation.execution_failed.ForcePrepareCompensation
 */
export type ForcePrepareCompensation = Record<string, any>;

/**
 * function_changed
 * - key: compensation.execution_failed.FunctionChanged
 */
export interface FunctionChanged {
    contextName: string;
    functionKind: FunctionKind;
    name: string;
    processorName: string;
}

/**
 * mark_recoverable
 * - key: compensation.execution_failed.MarkRecoverable
 */
export interface MarkRecoverable {
    recoverable: RecoverableType;
}

/**
 * prepare_compensation
 * - key: compensation.execution_failed.PrepareCompensation
 */
export type PrepareCompensation = Record<string, any>;

/**
 * recoverable_marked
 * - key: compensation.execution_failed.RecoverableMarked
 */
export interface RecoverableMarked {
    recoverable: RecoverableType;
}

/** - key: compensation.execution_failed.RetrySpec */
export interface RetrySpec {
    /** - format: int32 */
    executionTimeout: number;
    /** - format: int32 */
    maxRetries: number;
    /** - format: int32 */
    minBackoff: number;
}

/**
 * retry_spec_applied
 * - key: compensation.execution_failed.RetrySpecApplied
 */
export interface RetrySpecApplied {
    /** - format: int32 */
    executionTimeout: number;
    /** - format: int32 */
    maxRetries: number;
    /** - format: int32 */
    minBackoff: number;
}

/** - key: compensation.execution_failed.RetryState */
export interface RetryState {
    /** - format: int64 */
    nextRetryAt: number;
    /** - format: int32 */
    retries: number;
    /** - format: int64 */
    retryAt: number;
    /** - format: int64 */
    timeoutAt: number;
}
