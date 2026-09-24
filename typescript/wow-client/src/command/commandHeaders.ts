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

import { WowHeaders } from '../types/headers.js';

/**
 * Command Header Constants
 *
 * The HTTP headers a Wow command route reads. Each value is a string literal
 * type, so `CommandRequestHeaders` can type the value of every header by its
 * name. {@link commandHeaders} and {@link waitStrategy} build them from typed
 * options.
 *
 * Mirrors `CommandComponent.Header` in
 * `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command/CommandComponent.kt`.
 *
 * @example
 * ```typescript
 * const headers: CommandRequestHeaders = {
 *   [CommandHeaders.TENANT_ID]: 'tenant-123',
 *   [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
 * };
 * ```
 */
export const CommandHeaders = Object.freeze({
  /** Prefix of the command headers */
  COMMAND_HEADERS_PREFIX: 'Command-',

  /** Tenant of the command, when the route has no tenant path segment */
  TENANT_ID: 'Command-Tenant-Id',

  /** Owner of the command, when the route has no owner path segment */
  OWNER_ID: 'Command-Owner-Id',

  /**
   * Space identifier header, `Wow-Space-Id`
   * Used to send the command into a space.
   *
   * The server shares this header with queries, so it has the `Wow-` prefix
   * rather than `Command-`; it is the same value as {@link WowHeaders.SPACE_ID}.
   */
  SPACE_ID: WowHeaders.SPACE_ID,

  /** Aggregate the command targets, when the route has no id path segment */
  AGGREGATE_ID: 'Command-Aggregate-Id',

  /** The aggregate version the command expects; a mismatch is a conflict */
  AGGREGATE_VERSION: 'Command-Aggregate-Version',

  /** Prefix of the wait headers */
  WAIT_PREFIX: 'Command-Wait-',

  /** How long the server waits for the wait stage, in milliseconds */
  WAIT_TIME_OUT: 'Command-Wait-Timeout',

  /** The stage to wait for; the server waits for `PROCESSED` when absent */
  WAIT_STAGE: 'Command-Wait-Stage',

  /** The bounded context whose processing the wait stage refers to */
  WAIT_CONTEXT: 'Command-Wait-Context',

  /** The processor whose processing the wait stage refers to */
  WAIT_PROCESSOR: 'Command-Wait-Processor',

  /** The function whose processing the wait stage refers to */
  WAIT_FUNCTION: 'Command-Wait-Function',

  /** Prefix of the wait-chain tail headers */
  WAIT_TAIL_PREFIX: 'Command-Wait-Tail-',

  /** The stage the command a saga sends must reach; only with `SAGA_HANDLED` */
  WAIT_TAIL_STAGE: 'Command-Wait-Tail-Stage',

  /** The bounded context of the wait-chain tail */
  WAIT_TAIL_CONTEXT: 'Command-Wait-Tail-Context',

  /** The processor of the wait-chain tail */
  WAIT_TAIL_PROCESSOR: 'Command-Wait-Tail-Processor',

  /** The function of the wait-chain tail */
  WAIT_TAIL_FUNCTION: 'Command-Wait-Tail-Function',

  /** Idempotency key of the command; a repeated one is refused */
  REQUEST_ID: 'Command-Request-Id',

  /** Whether the server may process the command locally, `true` or `false` */
  LOCAL_FIRST: 'Command-Local-First',

  /** Bounded context of the aggregate, for the `/wow/command/send` route */
  COMMAND_AGGREGATE_CONTEXT: 'Command-Aggregate-Context',

  /** Name of the aggregate, for the `/wow/command/send` route */
  COMMAND_AGGREGATE_NAME: 'Command-Aggregate-Name',

  /** Fully qualified command type, for the `/wow/command/send` route */
  COMMAND_TYPE: 'Command-Type',

  /** Prefix of the custom headers the server copies into the command header */
  COMMAND_HEADER_X_PREFIX: 'Command-Header-',
} as const);
