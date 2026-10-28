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

import { FrameworkErrorCodes, type Problem } from '../problem.js';
import type { AggregateRef, CommandResult, StateView } from '../wire.js';

/**
 * A command's result as a service answers it, for a test's fake: the command
 * `ref` names, committed (`succeeded` at `committed`, version 1) unless
 * `overrides` says otherwise. A `rejected` or `failed` one without an `error`
 * carries `wow.command.refused` or `wow.command.failed`.
 */
export function commandResultOf<R = Record<string, unknown>>(
  ref: AggregateRef,
  overrides: Partial<CommandResult<R>> = {},
): CommandResult<R> {
  const { context, name } = ref.type;
  const status = overrides.status ?? 'succeeded';
  const commandId = overrides.commandId ?? 'command-1';
  return {
    format: 1,
    commandId,
    requestId: 'request-1',
    correlationId: commandId,
    ref,
    hop: { stage: 'committed', context },
    function: { kind: 'command', context, processor: name, name: 'Command' },
    status,
    version: 1,
    duplicate: false,
    error:
      status === 'succeeded'
        ? null
        : {
            code:
              status === 'rejected'
                ? FrameworkErrorCodes.COMMAND_REFUSED
                : FrameworkErrorCodes.COMMAND_FAILED,
            message: `The command was ${status.toLowerCase()}.`,
          },
    results: {} as R,
    derived: [],
    signalTime: 0,
    ...overrides,
  };
}

/** The reason phrases of the statuses a Wow service answers a problem with. */
const TITLES: Readonly<Record<number, string>> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  410: 'Gone',
  412: 'Precondition Failed',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
};

/**
 * An RFC 9457 problem as a service answers it, of `status` and the Wow `code`
 * (`urn:wow:error:<code>`; `about:blank` without one), for a test's fake or
 * an expected error. Its title is the status's reason phrase unless
 * `overrides` says otherwise.
 */
export function problemOf(
  status: number,
  code?: string,
  overrides: Partial<Problem> = {},
): Problem {
  return {
    type: code === undefined ? 'about:blank' : `urn:wow:error:${code}`,
    title: TITLES[status] ?? `HTTP ${status}`,
    status,
    ...(code === undefined ? {} : { code }),
    ...overrides,
  };
}

/**
 * The response a service answers `problem` with: `application/problem+json`,
 * its extension members written as members of their own, as the wire carries
 * them.
 */
export function problemResponse(
  problem: Problem,
  init: ResponseInit = {},
): Response {
  const { extensions, ...members } = problem;
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/problem+json');
  return new Response(JSON.stringify({ ...extensions, ...members }), {
    ...init,
    status: problem.status,
    headers,
  });
}

/**
 * An aggregate's state as a state read answers it, for a test's fake: `ref`'s
 * `state` at version 1, of no owner or space, not deleted and untagged unless
 * `overrides` says otherwise.
 */
export function stateViewOf<S>(
  ref: AggregateRef,
  state: S,
  overrides: Partial<Omit<StateView<S>, 'ref' | 'state'>> = {},
): StateView<S> {
  return {
    ref,
    version: 1,
    scope: {},
    deleted: false,
    tags: {},
    state,
    ...overrides,
  };
}
