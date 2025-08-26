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


import { ServerSentEvent, ServerSentEventStream } from '@ahoo-wang/fetcher-eventstream';
import { CommandResult } from './commandResult';

export interface CommandResultEvent extends Omit<ServerSentEvent, 'data'> {
  data: CommandResult;
}

export class CommandResultEventTransform implements Transformer<ServerSentEvent, CommandResultEvent> {
  transform(
    chunk: ServerSentEvent,
    controller: TransformStreamDefaultController<CommandResultEvent>,
  ) {
    controller.enqueue({
      data: JSON.parse(chunk.data),
      event: chunk.event,
      id: chunk.id,
      retry: chunk.retry,
    });
  }
}

export class CommandResultEventTransformStream extends TransformStream<ServerSentEvent, CommandResultEvent> {
  constructor() {
    super(new CommandResultEventTransform());
  }
}

export interface CommandResultEventStream extends ReadableStream<CommandResultEvent> {
}

export function toCommandResultEventStream(
  serverSentEventStream: ServerSentEventStream,
): CommandResultEventStream {
  return serverSentEventStream.pipeThrough(new CommandResultEventTransformStream());
}