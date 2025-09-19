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
  AggregateIdCapable,
  AggregateNameCapable,
  ErrorInfo,
  FunctionInfoCapable,
  Identifier,
  NamedBoundedContext,
} from '../types';
import type {
  CommandId,
  CommandResultCapable,
  CommandStageCapable,
  NullableAggregateVersionCapable,
  RequestId,
  SignalTimeCapable,
  WaitCommandIdCapable,
} from './types';
import { type JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Represents a signal that waits for command execution completion.
 *
 * This interface combines multiple capabilities to provide comprehensive information
 * about a command that is being waited upon, including identification, timing,
 * execution results, and error information.
 *
 * It extends several interfaces to aggregate different aspects of command execution:
 * - Identifier: Provides unique identification
 * - WaitCommandIdCapable: Contains the ID of the command being waited for
 * - CommandId: Contains the command ID
 * - AggregateIdCapable: Provides the aggregate ID associated with the command
 * - NullableAggregateVersionCapable: Contains optional aggregate version for concurrency control
 * - ErrorInfo: Contains error information if command execution failed
 * - SignalTimeCapable: Provides timestamp information
 * - CommandResultCapable: Contains the actual command execution result
 * - FunctionInfoCapable: Provides information about the function that processed the command
 */
export interface WaitSignal
  extends Identifier,
    WaitCommandIdCapable,
    CommandId,
    AggregateIdCapable,
    NullableAggregateVersionCapable,
    ErrorInfo,
    SignalTimeCapable,
    CommandResultCapable,
    FunctionInfoCapable {
}

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
