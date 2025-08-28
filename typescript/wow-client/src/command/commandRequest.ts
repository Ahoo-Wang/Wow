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

import { NullableAggregateVersionCapable } from './types';
import { HttpMethod, RequestHeaders } from '@ahoo-wang/fetcher';

/**
 * Command request interface
 *
 * Represents a command request with all necessary metadata for execution.
 * This interface extends NullableAggregateVersionCapable to include optional aggregate version information.
 *
 * @example
 * ```typescript
 * const commandRequest: CommandRequest = {
 *   path: '/commands/user/CreateUser',
 *   method: HttpMethod.POST,
 *   headers: {
 *     [CommandHeaders.TENANT_ID]: 'tenant-123',
 *     [CommandHeaders.REQUEST_ID]: 'req-' + Date.now()
 *   },
 *   body: {
 *     name: 'John Doe',
 *     email: 'john@example.com'
 *   },
 *   timeout: 10000,
 *   localFirst: true,
 *   stream: false
 * };
 * ```
 */
export interface CommandRequest extends NullableAggregateVersionCapable {
  /**
   * The path for the command request
   */
  path: string;

  /**
   * Path parameters for the command request
   */
  pathParams?: Record<string, any>;

  /**
   * HTTP method for the command request
   */
  method: HttpMethod;

  /**
   * HTTP headers for the command request
   */
  headers: RequestHeaders;

  /**
   * Request body containing the command data
   */
  body: Record<string, any>;

  /**
   * Command timeout period in milliseconds
   */
  timeout?: number;

  /**
   * Aggregate ID for the command
   */
  aggregateId?: string;

  /**
   * The version of the target aggregate, which is used to control version conflicts
   */
  aggregateVersion?: number;

  /**
   * The request ID of the command message, which is used to check the idempotency of the command message
   */
  requestId?: string;

  /**
   * Whether to enable local priority mode, if false, it will be turned off, and the default is true.
   */
  localFirst?: boolean;

  /**
   * Whether to enable event stream mode, if true, it will be turned on, and the default is false.
   */
  stream?: boolean;
}
