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
   * Represents an error type that indicates the operation or error cannot be retried.
   *
   * This enum value is used to classify errors in a way that signifies the error condition is permanent and retrying the operation will not resolve the issue. It is particularly
   *  useful for handling errors where the underlying problem is fundamental and cannot be resolved by simply retrying, such as invalid input, resource exhaustion, or other non-transient
   *  issues.
   */
  UNKNOWN = 'UNKNOWN',

  /**
   * Represents an unknown type of recoverability for an error or operation.
   * This is used when the recoverability of an error cannot be determined or is not specified.
   */
  UNRECOVERABLE = 'UNRECOVERABLE'
}

/**
 * Represents an error that occurs during the binding process, typically when data is being mapped to or from an object.
 * This class extends the [Named] interface, inheriting the `name` property which can be used to identify the source or context of the error.
 *
 * @param name The name or identifier for the context in which the error occurred.
 * @param msg A message describing the error.
 */
export interface BindingError {
  name: string;
  msg: string;
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
  errorCode: string;
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
 * A constant representing a successful operation or status.
 * This value is typically used in the context of error handling and response descriptions to indicate that an operation has been completed successfully.
 */
export const SUCCEEDED_ERROR_CODE = 'Ok';