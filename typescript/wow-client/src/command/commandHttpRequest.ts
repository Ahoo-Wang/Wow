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

import { RequestHeaders, UrlParams } from '@ahoo-wang/fetcher';
import { CommandHeaders } from './commandHeaders';
import { ParameterRequest } from '@ahoo-wang/fetcher-decorator';
import { PathParams } from '../types/endpoints';

/**
 * Command HTTP Headers Interface
 *
 * Defines the HTTP header fields used in command processing within the Wow framework.
 * This interface extends RequestHeaders to provide type-safe access to all command-related headers.
 *
 * @example
 * ```typescript
 * // Using CommandHttpHeaders in a request
 * const headers: CommandHttpHeaders = {
 *   [CommandHeaders.TENANT_ID]: 'tenant-123',
 *   [CommandHeaders.AGGREGATE_ID]: 'aggregate-456',
 *   [CommandHeaders.REQUEST_ID]: 'request-789'
 * };
 * ```
 */
export interface CommandHttpHeaders extends RequestHeaders {
  /**
   * Tenant identifier header
   * Used to identify the tenant context for the command
   */
  [CommandHeaders.TENANT_ID]: string;

  /**
   * Owner identifier header
   * Used to identify the owner context for the command
   */
  [CommandHeaders.OWNER_ID]: string;

  /**
   * Aggregate identifier header
   * Used to identify the aggregate root for the command
   */
  [CommandHeaders.AGGREGATE_ID]: string;

  /**
   * Aggregate version header
   * Used to specify the expected version of the aggregate root
   */
  [CommandHeaders.AGGREGATE_VERSION]: string;

  /**
   * Wait timeout header
   * Specifies the maximum time to wait for command processing
   */
  [CommandHeaders.WAIT_TIME_OUT]: string;

  /**
   * Wait stage header
   * Specifies the processing stage to wait for
   */
  [CommandHeaders.WAIT_STAGE]: string;

  /**
   * Wait context header
   * Specifies the bounded context to wait for
   */
  [CommandHeaders.WAIT_CONTEXT]: string;

  /**
   * Wait processor header
   * Specifies the processor to wait for
   */
  [CommandHeaders.WAIT_PROCESSOR]: string;

  /**
   * Wait function header
   * Specifies the function to wait for
   */
  [CommandHeaders.WAIT_FUNCTION]: string;

  /**
   * Wait tail stage header
   * Specifies the tail processing stage to wait for
   */
  [CommandHeaders.WAIT_TAIL_STAGE]: string;

  /**
   * Wait tail context header
   * Specifies the tail bounded context to wait for
   */
  [CommandHeaders.WAIT_TAIL_CONTEXT]: string;

  /**
   * Wait tail processor header
   * Specifies the tail processor to wait for
   */
  [CommandHeaders.WAIT_TAIL_PROCESSOR]: string;

  /**
   * Wait tail function header
   * Specifies the tail function to wait for
   */
  [CommandHeaders.WAIT_TAIL_FUNCTION]: string;

  /**
   * Request identifier header
   * Used to track the request ID for correlation
   */
  [CommandHeaders.REQUEST_ID]: string;

  /**
   * Local first header
   * Indicates whether to prefer local processing
   */
  [CommandHeaders.LOCAL_FIRST]: string;

  /**
   * Command aggregate context header
   * Specifies the bounded context of the aggregate
   */
  [CommandHeaders.COMMAND_AGGREGATE_CONTEXT]: string;

  /**
   * Command aggregate name header
   * Specifies the name of the aggregate
   */
  [CommandHeaders.COMMAND_AGGREGATE_NAME]: string;

  /**
   * Command type header
   * Specifies the type of the command
   */
  [CommandHeaders.COMMAND_TYPE]: string;
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
export interface CommandHttpRequest extends ParameterRequest {
  /**
   * Path for the command endpoint (relative to class base path).
   */
  path: string;
  urlParams?: CommandUrlParams;
  headers?: CommandHttpHeaders;
  /**
   * The body of the command request.
   */
  body: any;
}
