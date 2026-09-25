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

import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import type { CommandResult } from '../../model/index.js';

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
