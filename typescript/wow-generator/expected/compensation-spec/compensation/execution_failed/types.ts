import { AggregateId, BindingError, FunctionInfo, FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";

/**
 * apply_execution_failed
 * - key: compensation.execution_failed.ApplyExecutionFailed
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "error": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ErrorDetails"
 *     },
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     }
 *   },
 *   "required": [
 *     "error",
 *     "executeAt"
 *   ],
 *   "title": "apply_execution_failed",
 *   "description": ""
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     }
 *   },
 *   "required": [
 *     "executeAt"
 *   ],
 *   "title": "apply_execution_success",
 *   "description": ""
 * }
 * ```
 */
export interface ApplyExecutionSuccess {
    /** - format: int64 */
    executeAt: number;
}

/**
 * apply_retry_spec
 * - key: compensation.execution_failed.ApplyRetrySpec
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "executionTimeout": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "maxRetries": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "minBackoff": {
 *       "type": "integer",
 *       "format": "int32"
 *     }
 *   },
 *   "required": [
 *     "executionTimeout",
 *     "maxRetries",
 *     "minBackoff"
 *   ],
 *   "title": "apply_retry_spec",
 *   "description": ""
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "contextName": {
 *       "type": "string"
 *     },
 *     "functionKind": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionKind"
 *     },
 *     "name": {
 *       "type": "string"
 *     },
 *     "processorName": {
 *       "type": "string"
 *     }
 *   },
 *   "required": [
 *     "contextName",
 *     "functionKind",
 *     "name",
 *     "processorName"
 *   ],
 *   "title": "change_function",
 *   "description": ""
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "eventId": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.EventId"
 *     },
 *     "function": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionInfoData"
 *     },
 *     "retryState": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.RetryState"
 *     }
 *   },
 *   "required": [
 *     "eventId",
 *     "function",
 *     "retryState"
 *   ],
 *   "title": "compensation_prepared"
 * }
 * ```
 */
export interface CompensationPrepared {
    eventId: EventId;
    function: FunctionInfo;
    retryState: RetryState;
}

/**
 * create_execution_failed
 * - key: compensation.execution_failed.CreateExecutionFailed
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "error": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ErrorDetails"
 *     },
 *     "eventId": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.EventId"
 *     },
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "function": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionInfoData"
 *     },
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     },
 *     "retrySpec": {
 *       "anyOf": [
 *         {
 *           "type": "null"
 *         },
 *         {
 *           "$ref": "#/components/schemas/compensation.execution_failed.RetrySpec"
 *         }
 *       ]
 *     }
 *   },
 *   "required": [
 *     "error",
 *     "eventId",
 *     "executeAt",
 *     "function"
 *   ],
 *   "title": "create_execution_failed",
 *   "description": ""
 * }
 * ```
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

/**
 * - key: compensation.execution_failed.ErrorDetails
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "bindingErrors": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/wow.api.BindingError"
 *       }
 *     },
 *     "errorCode": {
 *       "type": "string"
 *     },
 *     "errorMsg": {
 *       "type": "string"
 *     },
 *     "stackTrace": {
 *       "type": "string"
 *     },
 *     "succeeded": {
 *       "type": "boolean",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "errorCode",
 *     "errorMsg",
 *     "stackTrace"
 *   ]
 * }
 * ```
 */
export interface ErrorDetails {
    bindingErrors: BindingError[];
    errorCode: string;
    errorMsg: string;
    stackTrace: string;
    readonly succeeded: boolean;
}

/**
 * - key: compensation.execution_failed.EventId
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "aggregateId": {
 *       "$ref": "#/components/schemas/wow.api.modeling.AggregateId"
 *     },
 *     "id": {
 *       "type": "string"
 *     },
 *     "version": {
 *       "type": "integer",
 *       "format": "int32"
 *     }
 *   },
 *   "required": [
 *     "aggregateId",
 *     "id",
 *     "version"
 *   ]
 * }
 * ```
 */
export interface EventId {
    aggregateId: AggregateId;
    id: string;
    /** - format: int32 */
    version: number;
}

/**
 * - key: compensation.execution_failed.ExecutionFailedAggregatedFields
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "",
 *     "aggregateId",
 *     "tenantId",
 *     "ownerId",
 *     "version",
 *     "eventId",
 *     "firstOperator",
 *     "operator",
 *     "firstEventTime",
 *     "eventTime",
 *     "deleted",
 *     "state",
 *     "state.error",
 *     "state.error.bindingErrors",
 *     "state.error.bindingErrors.msg",
 *     "state.error.bindingErrors.name",
 *     "state.error.errorCode",
 *     "state.error.errorMsg",
 *     "state.error.stackTrace",
 *     "state.error.succeeded",
 *     "state.eventId",
 *     "state.eventId.aggregateId",
 *     "state.eventId.aggregateId.namedAggregate",
 *     "state.eventId.aggregateId.namedAggregate.aggregateName",
 *     "state.eventId.aggregateId.namedAggregate.contextName",
 *     "state.eventId.aggregateId.id",
 *     "state.eventId.aggregateId.tenantId",
 *     "state.eventId.id",
 *     "state.eventId.version",
 *     "state.executeAt",
 *     "state.function",
 *     "state.function.contextName",
 *     "state.function.functionKind",
 *     "state.function.name",
 *     "state.function.processorName",
 *     "state.id",
 *     "state.recoverable",
 *     "state.retrySpec",
 *     "state.retrySpec.executionTimeout",
 *     "state.retrySpec.maxRetries",
 *     "state.retrySpec.minBackoff",
 *     "state.retryState",
 *     "state.retryState.nextRetryAt",
 *     "state.retryState.retries",
 *     "state.retryState.retryAt",
 *     "state.retryState.timeoutAt",
 *     "state.status",
 *     "state.isBelowRetryThreshold",
 *     "state.isRetryable"
 *   ]
 * }
 * ```
 */
export enum ExecutionFailedAggregatedFields {
    AGGREGATE_ID = `aggregateId`,
    TENANT_ID = `tenantId`,
    OWNER_ID = `ownerId`,
    VERSION = `version`,
    EVENT_ID = `eventId`,
    FIRST_OPERATOR = `firstOperator`,
    OPERATOR = `operator`,
    FIRST_EVENT_TIME = `firstEventTime`,
    EVENT_TIME = `eventTime`,
    DELETED = `deleted`,
    STATE = `state`,
    STATE_ERROR = `state.error`,
    STATE_ERROR_BINDING_ERRORS = `state.error.bindingErrors`,
    STATE_ERROR_BINDING_ERRORS_MSG = `state.error.bindingErrors.msg`,
    STATE_ERROR_BINDING_ERRORS_NAME = `state.error.bindingErrors.name`,
    STATE_ERROR_ERROR_CODE = `state.error.errorCode`,
    STATE_ERROR_ERROR_MSG = `state.error.errorMsg`,
    STATE_ERROR_STACK_TRACE = `state.error.stackTrace`,
    STATE_ERROR_SUCCEEDED = `state.error.succeeded`,
    STATE_EVENT_ID = `state.eventId`,
    STATE_EVENT_ID_AGGREGATE_ID = `state.eventId.aggregateId`,
    STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE = `state.eventId.aggregateId.namedAggregate`,
    STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE_AGGREGATE_NAME = `state.eventId.aggregateId.namedAggregate.aggregateName`,
    STATE_EVENT_ID_AGGREGATE_ID_NAMED_AGGREGATE_CONTEXT_NAME = `state.eventId.aggregateId.namedAggregate.contextName`,
    STATE_EVENT_ID_AGGREGATE_ID_ID = `state.eventId.aggregateId.id`,
    STATE_EVENT_ID_AGGREGATE_ID_TENANT_ID = `state.eventId.aggregateId.tenantId`,
    STATE_EVENT_ID_ID = `state.eventId.id`,
    STATE_EVENT_ID_VERSION = `state.eventId.version`,
    STATE_EXECUTE_AT = `state.executeAt`,
    STATE_FUNCTION = `state.function`,
    STATE_FUNCTION_CONTEXT_NAME = `state.function.contextName`,
    STATE_FUNCTION_FUNCTION_KIND = `state.function.functionKind`,
    STATE_FUNCTION_NAME = `state.function.name`,
    STATE_FUNCTION_PROCESSOR_NAME = `state.function.processorName`,
    STATE_ID = `state.id`,
    STATE_RECOVERABLE = `state.recoverable`,
    STATE_RETRY_SPEC = `state.retrySpec`,
    STATE_RETRY_SPEC_EXECUTION_TIMEOUT = `state.retrySpec.executionTimeout`,
    STATE_RETRY_SPEC_MAX_RETRIES = `state.retrySpec.maxRetries`,
    STATE_RETRY_SPEC_MIN_BACKOFF = `state.retrySpec.minBackoff`,
    STATE_RETRY_STATE = `state.retryState`,
    STATE_RETRY_STATE_NEXT_RETRY_AT = `state.retryState.nextRetryAt`,
    STATE_RETRY_STATE_RETRIES = `state.retryState.retries`,
    STATE_RETRY_STATE_RETRY_AT = `state.retryState.retryAt`,
    STATE_RETRY_STATE_TIMEOUT_AT = `state.retryState.timeoutAt`,
    STATE_STATUS = `state.status`,
    STATE_IS_BELOW_RETRY_THRESHOLD = `state.isBelowRetryThreshold`,
    STATE_IS_RETRYABLE = `state.isRetryable`
}

/**
 * execution_failed_applied
 * - key: compensation.execution_failed.ExecutionFailedApplied
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "error": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ErrorDetails"
 *     },
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     }
 *   },
 *   "required": [
 *     "error",
 *     "executeAt"
 *   ],
 *   "title": "execution_failed_applied"
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "error": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ErrorDetails"
 *     },
 *     "eventId": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.EventId"
 *     },
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "function": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionInfoData"
 *     },
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     },
 *     "retrySpec": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.RetrySpec"
 *     },
 *     "retryState": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.RetryState"
 *     }
 *   },
 *   "required": [
 *     "error",
 *     "eventId",
 *     "executeAt",
 *     "function",
 *     "retrySpec",
 *     "retryState"
 *   ],
 *   "title": "execution_failed_created"
 * }
 * ```
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

/**
 * - key: compensation.execution_failed.ExecutionFailedState
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "error": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ErrorDetails",
 *       "readOnly": true
 *     },
 *     "eventId": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.EventId",
 *       "readOnly": true
 *     },
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "function": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionInfoData",
 *       "readOnly": true
 *     },
 *     "id": {
 *       "type": "string"
 *     },
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     },
 *     "retrySpec": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.RetrySpec",
 *       "readOnly": true
 *     },
 *     "retryState": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.RetryState",
 *       "readOnly": true
 *     },
 *     "status": {
 *       "$ref": "#/components/schemas/compensation.execution_failed.ExecutionFailedStatus"
 *     },
 *     "isBelowRetryThreshold": {
 *       "type": "boolean",
 *       "readOnly": true
 *     },
 *     "isRetryable": {
 *       "type": "boolean",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "id"
 *   ]
 * }
 * ```
 */
export interface ExecutionFailedState {
    readonly error: ErrorDetails;
    readonly eventId: EventId;
    /** - format: int64 */
    executeAt: number;
    readonly function: FunctionInfo;
    id: string;
    recoverable: RecoverableType;
    readonly retrySpec: RetrySpec;
    readonly retryState: RetryState;
    status: ExecutionFailedStatus;
    readonly isBelowRetryThreshold: boolean;
    readonly isRetryable: boolean;
}

/**
 * - key: compensation.execution_failed.ExecutionFailedStatus
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "FAILED",
 *     "PREPARED",
 *     "SUCCEEDED"
 *   ]
 * }
 * ```
 */
export enum ExecutionFailedStatus {
    FAILED = `FAILED`,
    PREPARED = `PREPARED`,
    SUCCEEDED = `SUCCEEDED`
}

/**
 * execution_success_applied
 * - key: compensation.execution_failed.ExecutionSuccessApplied
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "executeAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     }
 *   },
 *   "required": [
 *     "executeAt"
 *   ],
 *   "title": "execution_success_applied"
 * }
 * ```
 */
export interface ExecutionSuccessApplied {
    /** - format: int64 */
    executeAt: number;
}

/**
 * force_prepare_compensation
 * - key: compensation.execution_failed.ForcePrepareCompensation
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "force_prepare_compensation",
 *   "description": ""
 * }
 * ```
 */
export type ForcePrepareCompensation = Record<string, any>;

/**
 * function_changed
 * - key: compensation.execution_failed.FunctionChanged
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "contextName": {
 *       "type": "string"
 *     },
 *     "functionKind": {
 *       "$ref": "#/components/schemas/wow.api.messaging.FunctionKind"
 *     },
 *     "name": {
 *       "type": "string"
 *     },
 *     "processorName": {
 *       "type": "string"
 *     }
 *   },
 *   "required": [
 *     "contextName",
 *     "functionKind",
 *     "name",
 *     "processorName"
 *   ],
 *   "title": "function_changed"
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     }
 *   },
 *   "required": [
 *     "recoverable"
 *   ],
 *   "title": "mark_recoverable",
 *   "description": ""
 * }
 * ```
 */
export interface MarkRecoverable {
    recoverable: RecoverableType;
}

/**
 * prepare_compensation
 * - key: compensation.execution_failed.PrepareCompensation
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "prepare_compensation",
 *   "description": ""
 * }
 * ```
 */
export type PrepareCompensation = Record<string, any>;

/**
 * recoverable_marked
 * - key: compensation.execution_failed.RecoverableMarked
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "recoverable": {
 *       "$ref": "#/components/schemas/wow.api.RecoverableType"
 *     }
 *   },
 *   "required": [
 *     "recoverable"
 *   ],
 *   "title": "recoverable_marked"
 * }
 * ```
 */
export interface RecoverableMarked {
    recoverable: RecoverableType;
}

/**
 * - key: compensation.execution_failed.RetrySpec
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "executionTimeout": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "maxRetries": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "minBackoff": {
 *       "type": "integer",
 *       "format": "int32"
 *     }
 *   },
 *   "required": [
 *     "executionTimeout",
 *     "maxRetries",
 *     "minBackoff"
 *   ]
 * }
 * ```
 */
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "executionTimeout": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "maxRetries": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "minBackoff": {
 *       "type": "integer",
 *       "format": "int32"
 *     }
 *   },
 *   "required": [
 *     "executionTimeout",
 *     "maxRetries",
 *     "minBackoff"
 *   ],
 *   "title": "retry_spec_applied"
 * }
 * ```
 */
export interface RetrySpecApplied {
    /** - format: int32 */
    executionTimeout: number;
    /** - format: int32 */
    maxRetries: number;
    /** - format: int32 */
    minBackoff: number;
}

/**
 * - key: compensation.execution_failed.RetryState
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "nextRetryAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "retries": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "retryAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     },
 *     "timeoutAt": {
 *       "type": "integer",
 *       "format": "int64"
 *     }
 *   },
 *   "required": [
 *     "nextRetryAt",
 *     "retries",
 *     "retryAt",
 *     "timeoutAt"
 *   ]
 * }
 * ```
 */
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
