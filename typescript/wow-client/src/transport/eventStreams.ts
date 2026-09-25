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

import type { ResultExtractor } from '@ahoo-wang/fetcher';
import {
  JsonEventStreamResultExtractor,
  type JsonServerSentEvent,
} from '@ahoo-wang/fetcher-eventstream';
import { isErrorInfo, WowError } from '../error/wowError.js';
import { type CommandResult, CommandStage } from '../model/command.js';

/**
 * The event name the server gives the rows of a query stream. Wow sends them
 * without an `event:` field, which the event-stream parser reads as
 * `message`.
 */
const ROW_EVENT = 'message';

const COMMAND_STAGES: ReadonlySet<string> = new Set(
  Object.values(CommandStage),
);

/**
 * Passes the events `isData` accepts and errors the stream with a `WowError`
 * at the first one it does not.
 *
 * When a server-sent event stream fails midway, Wow still answers HTTP 200
 * and sends one last event whose name is the error code and whose data is
 * the `ErrorInfo`, then closes the stream (`WebFluxResponseStrategy`,
 * `errorResume`). Read as data it would pass for a row or a command result;
 * here it ends the stream the way a failed request ends a call.
 */
function failOnErrorEvent<T>(
  isData: (eventName: string) => boolean,
): TransformStream<JsonServerSentEvent<unknown>, JsonServerSentEvent<T>> {
  return new TransformStream({
    transform(event, controller) {
      if (isData(event.event)) {
        controller.enqueue(event as JsonServerSentEvent<T>);
        return;
      }
      controller.error(
        new WowError(
          isErrorInfo(event.data)
            ? event.data
            : { errorCode: event.event, errorMsg: '' },
        ),
      );
    },
  });
}

/**
 * The result extractor of the query streams (`listStream`,
 * `listStateStream`, `aggregateStream`, …): the response as a stream of JSON
 * server-sent events, which errors with a {@link WowError} when the server
 * sends an error event.
 */
export const QueryEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<unknown>>
> = async exchange =>
  (await JsonEventStreamResultExtractor(exchange)).pipeThrough(
    failOnErrorEvent(eventName => eventName === ROW_EVENT),
  );

/**
 * The result extractor of `sendAndWaitStream`: the command results, one per
 * stage the command reached, which errors with a {@link WowError} when the
 * server sends an error event. A command result whose own `errorCode` is not
 * `Ok` is still a result — the command failed in its processing — and is
 * passed on as one.
 *
 * Generated command clients can take it as their `resultExtractor`.
 */
export const CommandResultEventStreamResultExtractor: ResultExtractor<
  ReadableStream<JsonServerSentEvent<CommandResult>>
> = async exchange =>
  (await JsonEventStreamResultExtractor(exchange)).pipeThrough(
    failOnErrorEvent<CommandResult>(eventName => COMMAND_STAGES.has(eventName)),
  );
