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

import { FRAMEWORK_TITLES } from './frameworkTitles.js';
import {
  FRAMEWORK_RETRIES,
  FrameworkErrorCodes,
  type Problem,
  retryOf,
} from '../problem.js';
import {
  type AggregateRef,
  type CommandResult,
  CommandResultStatus,
  FunctionKind,
  Stage,
  type Snapshot,
} from '../wire.js';

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
  const status = overrides.status ?? CommandResultStatus.SUCCEEDED;
  const commandId = overrides.commandId ?? 'command-1';
  return {
    format: 1,
    commandId,
    requestId: 'request-1',
    correlationId: commandId,
    ref,
    hop: { stage: Stage.COMMITTED, context },
    function: {
      kind: FunctionKind.COMMAND,
      context,
      processor: name,
      name: 'Command',
    },
    status,
    version: 1,
    duplicate: false,
    error:
      status === CommandResultStatus.SUCCEEDED
        ? null
        : {
            code:
              status === CommandResultStatus.REJECTED
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
 * The `Retry-After` seconds of a problem a retry may get past whose code the
 * catalog gives none, as the service's `FrameworkErrors.RETRY_AFTER_SECONDS`.
 */
const RETRY_AFTER_SECONDS = 1;

/**
 * An RFC 9457 problem as a service answers it, of `status` and the Wow `code`
 * (`urn:wow:error:<code>`; `about:blank` without one), for a test's fake or
 * an expected error. As the service writes it, its `retry` is the error
 * catalog's for the code (`none` for any other code and for none), and its
 * title the catalog's for a framework code, else the status's reason phrase
 * (a service titles a domain code by its description in the Manifest, which
 * `overrides` gives). `overrides` win.
 */
export function problemOf(
  status: number,
  code?: string,
  overrides: Partial<Problem> = {},
): Problem {
  return {
    type: code === undefined ? 'about:blank' : `urn:wow:error:${code}`,
    title:
      (code === undefined ? undefined : FRAMEWORK_TITLES.get(code)) ??
      TITLES[status] ??
      `HTTP ${status}`,
    status,
    ...(code === undefined ? {} : { code }),
    retry:
      (code === undefined ? undefined : FRAMEWORK_RETRIES.get(code)?.retry) ??
      'none',
    ...overrides,
  };
}

/**
 * The response a service answers `problem` with: `application/problem+json`,
 * its extension members written as members of their own, as the wire carries
 * them, and `Retry-After` when a retry may get past it (`retry` other than
 * `none`): the catalog's seconds for its code, else 1, as the service writes
 * it. A `Retry-After` in `init.headers` wins.
 */
export function problemResponse(
  problem: Problem,
  init: ResponseInit = {},
): Response {
  const { extensions, ...members } = problem;
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/problem+json');
  if (
    (problem.retry ?? retryOf(problem)) !== 'none' &&
    !headers.has('Retry-After')
  ) {
    const known =
      problem.code === undefined
        ? undefined
        : FRAMEWORK_RETRIES.get(problem.code);
    headers.set(
      'Retry-After',
      String(
        known === undefined ? RETRY_AFTER_SECONDS : known.retryAfterMs / 1000,
      ),
    );
  }
  return new Response(JSON.stringify({ ...extensions, ...members }), {
    ...init,
    status: problem.status,
    headers,
  });
}

/**
 * An aggregate's snapshot as a state read and a snapshot query answer it, for
 * a test's fake: `ref`'s `state` at version 1, committed at 0 by an anonymous
 * caller, of no owner or space, not deleted, untagged and keeping no
 * schedules unless `overrides` says otherwise.
 */
export function snapshotOf<S>(
  ref: AggregateRef,
  state: S,
  overrides: Partial<
    Omit<Snapshot<S>, 'tenantId' | 'aggregateId' | 'state'>
  > = {},
): Snapshot<S> {
  return {
    tenantId: ref.tenantId,
    aggregateId: ref.id,
    version: 1,
    ownerId: null,
    spaceId: null,
    deleted: false,
    access: {},
    firstEventTime: 0,
    lastEventTime: 0,
    operator: null,
    firstOperator: null,
    schedules: {},
    state,
    ...overrides,
  };
}
