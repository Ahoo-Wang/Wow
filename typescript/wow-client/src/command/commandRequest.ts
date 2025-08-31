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

import { FetchRequestInit, RequestHeaders, UrlParams } from '@ahoo-wang/fetcher';
import { CommandHttpHeaders } from './commandHttpHeaders';
import { PathParams } from '../types/endpoints';

/**
 * Command Request Headers Interface
 *
 * Defines the HTTP header fields used in command processing within the Wow framework.
 * This interface extends RequestHeaders to provide type-safe access to all command-related headers.
 *
 * @example
 * ```typescript
 * // Using CommandRequestHeaders in a request
 * const headers: CommandRequestHeaders = {
 *   [CommandHeaders.TENANT_ID]: 'tenant-123',
 *   [CommandHeaders.AGGREGATE_ID]: 'aggregate-456',
 *   [CommandHeaders.REQUEST_ID]: 'request-789'
 * };
 * ```
 */
export interface CommandRequestHeaders extends RequestHeaders {
  /**
   * Tenant identifier header
   * Used to identify the tenant context for the command
   */
  [CommandHttpHeaders.TENANT_ID]: string;

  /**
   * Owner identifier header
   * Used to identify the owner context for the command
   */
  [CommandHttpHeaders.OWNER_ID]: string;

  /**
   * Aggregate identifier header
   * Used to identify the aggregate root for the command
   */
  [CommandHttpHeaders.AGGREGATE_ID]: string;

  /**
   * Aggregate version header
   * Used to specify the expected version of the aggregate root
   */
  [CommandHttpHeaders.AGGREGATE_VERSION]: string;

  /**
   * Wait timeout header
   * Specifies the maximum time to wait for command processing
   */
  [CommandHttpHeaders.WAIT_TIME_OUT]: string;

  /**
   * Wait stage header
   * Specifies the processing stage to wait for
   */
  [CommandHttpHeaders.WAIT_STAGE]: string;

  /**
   * Wait context header
   * Specifies the bounded context to wait for
   */
  [CommandHttpHeaders.WAIT_CONTEXT]: string;

  /**
   * Wait processor header
   * Specifies the processor to wait for
   */
  [CommandHttpHeaders.WAIT_PROCESSOR]: string;

  /**
   * Wait function header
   * Specifies the function to wait for
   */
  [CommandHttpHeaders.WAIT_FUNCTION]: string;

  /**
   * Wait tail stage header
   * Specifies the tail processing stage to wait for
   */
  [CommandHttpHeaders.WAIT_TAIL_STAGE]: string;

  /**
   * Wait tail context header
   * Specifies the tail bounded context to wait for
   */
  [CommandHttpHeaders.WAIT_TAIL_CONTEXT]: string;

  /**
   * Wait tail processor header
   * Specifies the tail processor to wait for
   */
  [CommandHttpHeaders.WAIT_TAIL_PROCESSOR]: string;

  /**
   * Wait tail function header
   * Specifies the tail function to wait for
   */
  [CommandHttpHeaders.WAIT_TAIL_FUNCTION]: string;

  /**
   * Request identifier header
   * Used to track the request ID for correlation
   */
  [CommandHttpHeaders.REQUEST_ID]: string;

  /**
   * Local first header
   * Indicates whether to prefer local processing
   */
  [CommandHttpHeaders.LOCAL_FIRST]: string;

  /**
   * Command aggregate context header
   * Specifies the bounded context of the aggregate
   */
  [CommandHttpHeaders.COMMAND_AGGREGATE_CONTEXT]: string;

  /**
   * Command aggregate name header
   * Specifies the name of the aggregate
   */
  [CommandHttpHeaders.COMMAND_AGGREGATE_NAME]: string;

  /**
   * Command type header
   * Specifies the type of the command
   */
  [CommandHttpHeaders.COMMAND_TYPE]: string;
}

export interface CommandUrlParams extends Omit<UrlParams, 'path' | 'query'> {
  path?: PathParams;
}

/**
 * Command HTTP Request Interface
 *
 * Extends RequestHeaders to provide type-safe access to command-related HTTP headers.
 * This interface includes only the essential command headers commonly used in HTTP requests.
 */
export interface CommandRequest extends FetchRequestInit {
  urlParams?: CommandUrlParams;
  headers?: CommandRequestHeaders;
  /**
   * The body of the command request.
   */
  body: any;
}
