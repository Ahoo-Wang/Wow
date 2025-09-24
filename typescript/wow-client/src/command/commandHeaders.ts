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
 * Command Header Constants
 *
 * Defines standard HTTP header constants used in command processing within the Wow framework.
 * These headers are used to pass metadata and control information between services.
 *
 * @example
 * ```typescript
 * // Using header constants in a request
 * const request = {
 *   headers: {
 *     [CommandHttpHeaders.TENANT_ID]: 'tenant-123',
 *     [CommandHttpHeaders.AGGREGATE_ID]: 'aggregate-456',
 *     [CommandHttpHeaders.REQUEST_ID]: 'request-789'
 *   },
 *   body: command
 * };
 * ```
 */
export class CommandHeaders {
  /**
   * Prefix for all command-related headers
   */
  static readonly COMMAND_HEADERS_PREFIX = 'Command-';

  /**
   * Tenant identifier header
   * Used to identify the tenant context for the command
   */
  static readonly TENANT_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Tenant-Id`;

  /**
   * Owner identifier header
   * Used to identify the owner context for the command
   */
  static readonly OWNER_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Owner-Id`;

  /**
   * Aggregate identifier header
   * Used to identify the aggregate root for the command
   */
  static readonly AGGREGATE_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Id`;

  /**
   * Aggregate version header
   * Used to specify the expected version of the aggregate root
   */
  static readonly AGGREGATE_VERSION = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Version`;

  /**
   * Wait prefix for wait-related headers
   */
  static readonly WAIT_PREFIX = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Wait-`;

  /**
   * Wait timeout header
   * Specifies the maximum time to wait for command processing
   */
  static readonly WAIT_TIME_OUT = `${CommandHeaders.WAIT_PREFIX}Timeout`;

  // region Wait Stage
  /**
   * Wait stage header
   * Specifies the processing stage to wait for
   */
  static readonly WAIT_STAGE = `${CommandHeaders.WAIT_PREFIX}Stage`;

  /**
   * Wait context header
   * Specifies the bounded context to wait for
   */
  static readonly WAIT_CONTEXT = `${CommandHeaders.WAIT_PREFIX}Context`;

  /**
   * Wait processor header
   * Specifies the processor to wait for
   */
  static readonly WAIT_PROCESSOR = `${CommandHeaders.WAIT_PREFIX}Processor`;

  /**
   * Wait function header
   * Specifies the function to wait for
   */
  static readonly WAIT_FUNCTION = `${CommandHeaders.WAIT_PREFIX}Function`;
  // endregion

  // region Wait Chain Tail
  /**
   * Wait tail prefix for wait chain tail-related headers
   */
  static readonly WAIT_TAIL_PREFIX = `${CommandHeaders.WAIT_PREFIX}Tail-`;

  /**
   * Wait tail stage header
   * Specifies the tail processing stage to wait for
   */
  static readonly WAIT_TAIL_STAGE = `${CommandHeaders.WAIT_TAIL_PREFIX}Stage`;

  /**
   * Wait tail context header
   * Specifies the tail bounded context to wait for
   */
  static readonly WAIT_TAIL_CONTEXT = `${CommandHeaders.WAIT_TAIL_PREFIX}Context`;

  /**
   * Wait tail processor header
   * Specifies the tail processor to wait for
   */
  static readonly WAIT_TAIL_PROCESSOR = `${CommandHeaders.WAIT_TAIL_PREFIX}Processor`;

  /**
   * Wait tail function header
   * Specifies the tail function to wait for
   */
  static readonly WAIT_TAIL_FUNCTION = `${CommandHeaders.WAIT_TAIL_PREFIX}Function`;
  // endregion

  /**
   * Request identifier header
   * Used to track the request ID for correlation
   */
  static readonly REQUEST_ID = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Request-Id`;

  /**
   * Local first header
   * Indicates whether to prefer local processing
   */
  static readonly LOCAL_FIRST = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Local-First`;

  /**
   * Command aggregate context header
   * Specifies the bounded context of the aggregate
   */
  static readonly COMMAND_AGGREGATE_CONTEXT = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Context`;

  /**
   * Command aggregate name header
   * Specifies the name of the aggregate
   */
  static readonly COMMAND_AGGREGATE_NAME = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Aggregate-Name`;

  /**
   * Command type header
   * Specifies the type of the command
   */
  static readonly COMMAND_TYPE = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Type`;

  /**
   * Command header prefix for custom headers
   * Used to prefix custom command headers
   */
  static readonly COMMAND_HEADER_X_PREFIX = `${CommandHeaders.COMMAND_HEADERS_PREFIX}Header-`;
}
