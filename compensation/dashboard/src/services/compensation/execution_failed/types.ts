import { AggregateId, BindingError, ConditionOptions, FieldSort, FunctionInfo, FunctionKind, Operator, Pagination, Projection, RecoverableType } from "@ahoo-wang/fetcher-wow";

/** apply_execution_failed */
export interface ApplyExecutionFailed {
    error: ErrorDetails;
    executeAt: number;
    recoverable: RecoverableType;
}

/** apply_execution_success */
export interface ApplyExecutionSuccess {
    executeAt: number;
}

/** apply_retry_spec */
export interface ApplyRetrySpec {
    executionTimeout: number;
    maxRetries: number;
    minBackoff: number;
}

/** change_function */
export interface ChangeFunction {
    contextName: string;
    functionKind: FunctionKind;
    name: string;
    processorName: string;
}

/** compensation_prepared */
export interface CompensationPrepared {
    eventId: EventId;
    function: FunctionInfo;
    retryState: RetryState;
}

/** create_execution_failed */
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

export interface ExecutionFailedAggregatedCondition {
    children: ExecutionFailedAggregatedCondition[];
    field: ExecutionFailedAggregatedFields;
    operator: Operator;
    options: ConditionOptions;
    value: any;
}

export interface ExecutionFailedAggregatedDomainEventStream {
    /** The ID of the domain event stream. */
    id: string;
    /** The name of the context to which the domain event stream belongs */
    contextName: string;
    /** The name of the aggregate to which the domain event stream belongs */
    aggregateName: string;
    /** message header */
    header: ExecutionFailedAggregatedDomainEventStreamHeader;
    /** The tenant id of the aggregate */
    tenantId: string;
    /** The id of the aggregate */
    aggregateId: string;
    /** The owner ID of the aggregate resource */
    ownerId: string;
    /** The ID of the command message. */
    commandId: string;
    /** The request ID of the command message, which is used to check the idempotency of the command message */
    requestId: string;
    /** The version of the domain event stream */
    version: number;
    /** A list of domain events for the domain event stream */
    body: any[];
    /** The time when the domain event stream was created */
    createTime: number;
}

/** message header */
export interface ExecutionFailedAggregatedDomainEventStreamHeader {
    /** user agent */
    user_agent: string;
    /** remote ip */
    remote_ip: string;
    /** trace id */
    trace_id: string;
    /** command operator */
    command_operator: string;
    /** local first */
    local_first: boolean;
    /** command wait endpoint */
    command_wait_endpoint: string;
    command_wait_stage: string;
}

export interface ExecutionFailedAggregatedDomainEventStreamPagedList {
    list: ExecutionFailedAggregatedDomainEventStream[];
    total: number;
}

export interface ExecutionFailedAggregatedDomainEventStreamServerSentEventNonNullData {
    data: ExecutionFailedAggregatedDomainEventStream;
    event: string | null;
    id: string | null;
    retry: number | null;
}

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

export interface ExecutionFailedAggregatedListQuery {
    condition: ExecutionFailedAggregatedCondition;
    limit: number;
    projection: Projection;
    sort: FieldSort[];
}

export interface ExecutionFailedAggregatedPagedQuery {
    condition: ExecutionFailedAggregatedCondition;
    pagination: Pagination;
    projection: Projection;
    sort: FieldSort[];
}

export interface ExecutionFailedAggregatedSingleQuery {
    condition: ExecutionFailedAggregatedCondition;
    projection: Projection;
    sort: FieldSort[];
}

/** execution_failed_applied */
export interface ExecutionFailedApplied {
    error: ErrorDetails;
    executeAt: number;
    recoverable: RecoverableType;
}

/** execution_failed_created */
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

export interface ExecutionFailedStateMaterializedSnapshot {
    aggregateId: string;
    aggregateName: string;
    contextName: string;
    /** Whether the aggregate is deleted. */
    deleted: boolean;
    /** The event id of the aggregate. */
    eventId: string;
    /** The last event time of the aggregate, represented as a Unix timestamp in milliseconds. */
    eventTime: number;
    /** The first event time of the aggregate, represented as a Unix timestamp in milliseconds. */
    firstEventTime: number;
    /** The first operator of the aggregate. */
    firstOperator: string;
    /** The last operator of the aggregate. */
    operator: string;
    ownerId: string;
    /** The snapshot time of the aggregate, represented as a Unix timestamp in milliseconds. */
    snapshotTime: number;
    state: ExecutionFailedState;
    tenantId: string;
    version: number;
}

export interface ExecutionFailedStateMaterializedSnapshotPagedList {
    list: ExecutionFailedStateMaterializedSnapshot[];
    total: number;
}

export interface ExecutionFailedStateMaterializedSnapshotServerSentEventNonNullData {
    data: ExecutionFailedStateMaterializedSnapshot;
    event: string | null;
    id: string | null;
    retry: number | null;
}

export interface ExecutionFailedStatePagedList {
    list: ExecutionFailedState[];
    total: number;
}

export interface ExecutionFailedStateServerSentEventNonNullData {
    data: ExecutionFailedState;
    event: string | null;
    id: string | null;
    retry: number | null;
}

export interface ExecutionFailedStateSnapshot {
    /** The name of the context to which the snapshot belongs */
    contextName: string;
    /** The name of the aggregate to which the snapshot belongs */
    aggregateName: string;
    /** The tenant id of the aggregate */
    tenantId: string;
    /** The id of the aggregate */
    aggregateId: string;
    /** The owner ID of the aggregate resource */
    ownerId: string;
    /** The version of the snapshot */
    version: number;
    /** The ID of the domain event stream. */
    eventId: string;
    /** The first person who operates the aggregate is the creator */
    firstOperator: string;
    /** The last person who operates the aggregate */
    operator: string;
    /** The first event time of the aggregate, which is the time it was created */
    firstEventTime: number;
    /** The state data of the aggregate */
    state: ExecutionFailedStateSnapshotState;
    /** Whether the aggregate has been deleted */
    deleted: boolean;
    /** The snapshot time of the aggregate */
    snapshotTime: number;
}

/** The state data of the aggregate */
export interface ExecutionFailedStateSnapshotState {
    error: ErrorDetails;
    eventId: EventId;
    executeAt: number;
    function: FunctionInfo;
    id: string;
    recoverable: RecoverableType;
    retrySpec: RetrySpec;
    retryState: RetryState;
    status: ExecutionFailedStatus;
}

export interface ExecutionFailedStateStateEvent {
    /** The ID of the domain event stream. */
    id: string;
    /** The name of the context to which the domain event stream belongs */
    contextName: string;
    /** The name of the aggregate to which the domain event stream belongs */
    aggregateName: string;
    /** message header */
    header: ExecutionFailedStateStateEventHeader;
    /** The tenant id of the aggregate */
    tenantId: string;
    /** The id of the aggregate */
    aggregateId: string;
    /** The owner ID of the aggregate resource */
    ownerId: string;
    /** The ID of the command message. */
    commandId: string;
    /** The request ID of the command message, which is used to check the idempotency of the command message */
    requestId: string;
    /** The version of the domain event stream */
    version: number;
    /** A list of domain events for the domain event stream */
    body: ExecutionFailedStateStateEventBody[];
    /** The time when the domain event stream was created */
    createTime: number;
    /** The first person who operates the aggregate is the creator */
    firstOperator: string;
    /** The first event time of the aggregate, which is the time it was created */
    firstEventTime: number;
    /** The state data of the aggregate */
    state: ExecutionFailedStateStateEventState;
    /** Whether the aggregate has been deleted */
    deleted: boolean;
}

/** message header */
export interface ExecutionFailedStateStateEventHeader {
    /** user agent */
    user_agent: string;
    /** remote ip */
    remote_ip: string;
    /** trace id */
    trace_id: string;
    /** command operator */
    command_operator: string;
    /** local first */
    local_first: boolean;
    /** command wait endpoint */
    command_wait_endpoint: string;
    command_wait_stage: string;
}

/** The body of the domain event */
export interface ExecutionFailedStateStateEventBody {
    /** The ID of the domain event. */
    id: string;
    /** The name of the domain event */
    name: string;
    /** The revision number of the domain event */
    revision: string;
    /** The fully qualified name of the domain event body */
    bodyType: string;
    /** The message body of the domain event */
    body: any;
}

/** The state data of the aggregate */
export interface ExecutionFailedStateStateEventState {
    error: ErrorDetails;
    eventId: EventId;
    executeAt: number;
    function: FunctionInfo;
    id: string;
    recoverable: RecoverableType;
    retrySpec: RetrySpec;
    retryState: RetryState;
    status: ExecutionFailedStatus;
}

export enum ExecutionFailedStatus {
    FAILED = 'FAILED',
    PREPARED = 'PREPARED',
    SUCCEEDED = 'SUCCEEDED'
}

/** execution_success_applied */
export interface ExecutionSuccessApplied {
    executeAt: number;
}

/** force_prepare_compensation */
export interface ForcePrepareCompensation {
}

/** function_changed */
export interface FunctionChanged {
    contextName: string;
    functionKind: FunctionKind;
    name: string;
    processorName: string;
}

/** mark_recoverable */
export interface MarkRecoverable {
    recoverable: RecoverableType;
}

/** prepare_compensation */
export interface PrepareCompensation {
}

/** recoverable_marked */
export interface RecoverableMarked {
    recoverable: RecoverableType;
}

export interface RetrySpec {
    executionTimeout: number;
    maxRetries: number;
    minBackoff: number;
}

/** retry_spec_applied */
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
