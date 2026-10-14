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

import {
  type JsonServerSentEvent,
  toJsonServerSentEventStream,
  toServerSentEventStream,
} from '@ahoo-wang/fetcher-eventstream';
import type { CommandResult } from './model.js';
import { isProblem, WowProblemError } from './problem.js';

/** The event of each record of a streamed query list. */
const ROW_EVENT = 'row';

/** The event that ends a streamed query list that failed: the problem. */
const ERROR_EVENT = 'error';

/**
 * The results of a command's event stream (`Accept: text/event-stream`), one
 * per hop, each event named by its stage. A rejection or a failure after
 * admission is the stream's last result, not an error.
 */
export type CommandResultStream<R = Record<string, unknown>> = ReadableStream<
  CommandResult<R>
>;

/** The data of each event of `response`'s server-sent event stream. */
export function commandResults<R>(
  response: Response,
): CommandResultStream<R> {
  return toJsonServerSentEventStream<CommandResult<R>>(
    toServerSentEventStream(response),
  ).pipeThrough(
    new TransformStream<
      JsonServerSentEvent<CommandResult<R>>,
      CommandResult<R>
    >({
      transform(event, controller) {
        controller.enqueue(event.data);
      },
    }),
  );
}

/**
 * The records of a streamed query list: each `row` event's data, until an
 * `error` event, which errors the stream with its {@link WowProblemError}.
 */
export function queryRows<T>(response: Response): ReadableStream<T> {
  return toJsonServerSentEventStream<unknown>(
    toServerSentEventStream(response),
  ).pipeThrough(
    new TransformStream<JsonServerSentEvent<unknown>, T>({
      transform(event, controller) {
        if (event.event === ERROR_EVENT) {
          controller.error(
            new WowProblemError(
              isProblem(event.data)
                ? event.data
                : { type: 'about:blank', title: 'Query failed', status: 500 },
            ),
          );
          return;
        }
        if (event.event === ROW_EVENT || event.event === undefined) {
          controller.enqueue(event.data as T);
        }
      },
    }),
  );
}
