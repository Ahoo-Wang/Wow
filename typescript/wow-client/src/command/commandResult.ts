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

import type {
  AggregateId,
  AggregateNameCapable,
  ErrorInfo,
  FunctionInfoCapable,
  Identifier,
  NamedBoundedContext,
} from '../types';
import {
  CommandId,
  CommandResultCapable,
  CommandStageCapable,
  NullableAggregateVersionCapable,
  RequestId,
  SignalTimeCapable,
  WaitCommandIdCapable,
} from './types';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Command execution result interface
 *
 * Represents the result of a command execution, containing information about:
 * - Command identification (commandId, requestId)
 * - Execution context (bounded context, aggregate information)
 * - Execution status and errors
 * - Timing information (signalTime)
 * - Version tracking (aggregateVersion)
 */
export interface CommandResult
  extends Identifier,
    WaitCommandIdCapable,
    CommandStageCapable,
    NamedBoundedContext,
    AggregateNameCapable,
    AggregateId,
    ErrorInfo,
    CommandId,
    RequestId,
    ErrorInfo,
    FunctionInfoCapable,
    CommandResultCapable,
    SignalTimeCapable,
    NullableAggregateVersionCapable {
  /**
   * Aggregate root version number
   * - When command processing succeeds, this is the version number after the aggregate root has completed command processing
   * - When command validation fails at the command gateway, this is null
   * - When command execution fails in the command handler, this is the current version number of the aggregate root
   */
  aggregateVersion?: number;
}

/**
 * Command result event stream type
 *
 * A readable stream of JSON Server-Sent Events containing command execution results.
 * This stream allows real-time consumption of command results as they are processed.
 *
 * @example
 * ```typescript
 * const eventStream: CommandResultEventStream = getCommandResultStream();
 * for await (const event of eventStream) {
 *   const commandResult: CommandResult = event.data;
 *   console.log('Command result received:', commandResult);
 * }
 * ```
 */
export type CommandResultEventStream = ReadableStream<
  JsonServerSentEvent<CommandResult>
>;
