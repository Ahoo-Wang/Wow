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

import type { CommandResult } from '../../model/index.js';

/**
 * The stream `sendAndWaitStream` answers: one command result per stage the
 * command reached, in order. It errors with a `WowError` when the server
 * fails midway, so a `for await` over it throws.
 *
 * @example
 * ```typescript
 * declare const stream: CommandResultEventStream;
 * for await (const result of stream) {
 *   console.log(result.stage, result.errorCode);
 * }
 * ```
 */
export type CommandResultEventStream = ReadableStream<CommandResult>;
