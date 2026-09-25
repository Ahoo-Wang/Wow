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

import type { QueryErrorCode } from './queryErrorCodes.js';

/**
 * Enumerates the types of recoverability for errors or operations, allowing for classification and handling based on whether an error is transient and can be resolved by retrying.
 *
 * The [RecoverableType] enum provides a way to categorize errors into three distinct categories: [RECOVERABLE], [UNRECOVERABLE], and [UNKNOWN].
 * This categorization is essential for implementing robust error handling and retry mechanisms in applications,
 * ensuring that temporary issues are retried while permanent or unknown issues are handled appropriately.
 *
 */
export enum RecoverableType {
  /**
   * Represents an error type that indicates the operation or error can be retried.
   *
   * This enum value is used to classify errors in a way that allows for the implementation of retry logic. When an error
   * is marked as [RECOVERABLE], it signifies that the error condition is temporary and might be resolved upon retrying the
   * operation. This is particularly useful in scenarios where network issues, transient server errors, or other temporary
   * conditions may cause an operation to fail, but with a high likelihood of success on subsequent attempts.
   */
  RECOVERABLE = 'RECOVERABLE',
  /**
   * Represents an unknown type of recoverability for an error or operation.
   * This is used when the recoverability of an error cannot be determined or is not specified.
   */
  UNKNOWN = 'UNKNOWN',

  /**
   * Represents an error type that indicates the operation or error cannot be retried.
   *
   * This enum value is used to classify errors in a way that signifies the error condition is permanent and retrying the operation will not resolve the issue. It is particularly
   *  useful for handling errors where the underlying problem is fundamental and cannot be resolved by simply retrying, such as invalid input, resource exhaustion, or other non-transient
   *  issues.
   */
  UNRECOVERABLE = 'UNRECOVERABLE',
}

/**
 * Represents an error that occurs during the binding process, typically when data is being mapped to or from an object.
 * This class extends the [Named] interface, inheriting the `name` property which can be used to identify the source or context of the error.
 *
 * @param name The name or identifier for the context in which the error occurred.
 * @param msg A message describing the error.
 * @param code A stable, machine-readable code of the failed rule, when the server states one.
 */
export interface BindingError {
  name: string;
  msg: string;
  /**
   * A stable, machine-readable code of the rule that failed, when the server
   * states one: a rejected query carries one of {@link QueryErrorCodes}.
   * Absent otherwise, as for a command's validation errors.
   */
  code?: QueryErrorCode;
}

/**
 * Represents the information about an error, including whether the operation succeeded, the error code, and any associated messages or binding errors.
 *
 * This interface is designed to provide a standardized way of handling and representing errors across different parts of an application. It includes methods to check if the operation was successful, retrieve the error code
 * , and access any additional error details such as messages or binding errors.
 */
export interface ErrorInfo {
  /**
   * Represents the error code associated with an error. This value is used to identify the type of error that has occurred,
   * which can be useful for debugging, logging, and handling errors in a standardized way.
   */
  errorCode: ErrorCode;
  /**
   * Represents the message associated with an error. This message provides a human-readable description of the error, which can be used for logging, debugging, or displaying to the user
   * .
   */
  errorMsg: string;
  /**
   * Provides a list of [BindingError] instances that occurred during the binding process.
   * Each [BindingError] contains information about the error, including its name and a message describing the issue.
   * This property returns an empty list if no binding errors are present.
   */
  bindingErrors?: BindingError[];
}

/**
 * The error codes Wow itself answers with, as `ErrorInfo.errorCode`, as the
 * `Wow-Error-Code` response header, and as the event name of an error in a
 * server-sent event stream.
 *
 * Mirrors `ErrorCodes` in `wow-core/src/main/kotlin/me/ahoo/wow/exception/ErrorCodes.kt`
 * and the query schema and batch codes the server registers beside it.
 * Applications add their own codes; {@link ErrorCode} admits those too.
 */
export const ErrorCodes = Object.freeze({
  /** The request succeeded. */
  SUCCEEDED: 'Ok',
  /** The requested resource does not exist (HTTP 404). */
  NOT_FOUND: 'NotFound',
  /** The request is malformed (HTTP 400). */
  BAD_REQUEST: 'BadRequest',
  /** An argument is invalid (HTTP 400). */
  ILLEGAL_ARGUMENT: 'IllegalArgument',
  /** The aggregate is in a state that refuses the command (HTTP 400). */
  ILLEGAL_STATE: 'IllegalState',
  /** The wait timed out (HTTP 408). */
  REQUEST_TIMEOUT: 'RequestTimeout',
  /** The server is rate limiting (HTTP 429). */
  TOO_MANY_REQUESTS: 'TooManyRequests',
  /** The command's request id was used before (HTTP 400). */
  DUPLICATE_REQUEST_ID: 'DuplicateRequestId',
  /** The command failed validation (HTTP 400); see `bindingErrors`. */
  COMMAND_VALIDATION: 'CommandValidation',
  /** A command rewriter produced no command. */
  REWRITE_NO_COMMAND: 'RewriteNoCommand',
  /** Another command changed the aggregate first (HTTP 409). */
  EVENT_VERSION_CONFLICT: 'EventVersionConflict',
  /** The aggregate id already exists (HTTP 400). */
  DUPLICATE_AGGREGATE_ID: 'DuplicateAggregateId',
  /** The aggregate is not at the version the command expected (HTTP 409). */
  COMMAND_EXPECT_VERSION_CONFLICT: 'CommandExpectVersionConflict',
  /** The event stream is not at the version sourcing expected (HTTP 409). */
  SOURCING_VERSION_CONFLICT: 'SourcingVersionConflict',
  /** The aggregate is deleted (HTTP 410). */
  ILLEGAL_ACCESS_DELETED_AGGREGATE: 'IllegalAccessDeletedAggregate',
  /** The aggregate belongs to another owner (HTTP 403). */
  ILLEGAL_ACCESS_OWNER_AGGREGATE: 'IllegalAccessOwnerAggregate',
  /** The aggregate belongs to another space. */
  ILLEGAL_ACCESS_SPACE_AGGREGATE: 'IllegalAccessSpaceAggregate',
  /** A query lacks the authenticated tenant scope the server requires (HTTP 403). */
  ILLEGAL_ACCESS_QUERY_SCOPE: 'IllegalAccessQueryScope',
  /** An unexpected server failure (HTTP 500). */
  INTERNAL_SERVER_ERROR: 'InternalServerError',
  /** A query does not fit the aggregate's query schema (HTTP 400). */
  QUERY_SCHEMA_VALIDATION: 'QuerySchemaValidation',
  /** The query schema conflicts with the stored one (HTTP 500). */
  QUERY_SCHEMA_CONFLICT: 'QuerySchemaConflict',
  /** The query schema is not available yet (HTTP 503). */
  QUERY_SCHEMA_UNAVAILABLE: 'QuerySchemaUnavailable',
  /** A batch task failed. */
  BATCH_TASK_ERROR: 'BatchTaskError',
} as const);

/** One of the error codes Wow itself defines; see {@link ErrorCodes}. */
export type WowErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];

/**
 * An error code: one of Wow's own, which editors complete, or any other an
 * application defines.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export type ErrorCode = WowErrorCode | (string & {});
