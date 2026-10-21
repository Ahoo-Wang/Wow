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

import type { TextNumbers } from './exactJson.js';
import {
  expandPath,
  json,
  type RequestOptions,
  send,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
import type { CommandResult } from './wire.js';
import { rememberRequestId, WowProblemError } from './problem.js';
import { commandResults, type CommandResultStream } from './streams.js';
import { formatWaitPlan, formatWaitTimeout, type WaitOptions } from './wait.js';

/** What a command request carries beside its body (decisions D95–D99). */
export interface CommandOptions extends RequestOptions {
  /**
   * The request id (`Wow-Request-Id`): a retry with the same one is
   * deduplicated, answered as the first commit's duplicate. When absent, the
   * gateway generates one ({@link newRequestId}), which a failure carries
   * ({@link requestIdOf}): retry with it, not with a new one.
   */
  readonly requestId?: string;
  /** What to wait for, and for how long. */
  readonly wait?: WaitOptions;
  /** The version the aggregate must be at (`If-Match`). */
  readonly expectedVersion?: number;
  /**
   * The body's numbers written as their exact text, unquoted JSON numbers
   * (review R4-Q, Q-9; decision D122): a generated client sets it for a
   * command with `int64`, `bigint` or `decimal` fields, which it types as
   * `string`.
   */
  readonly bodyNumbers?: TextNumbers;
  /**
   * Resolve a result that did not succeed instead of rejecting with its
   * {@link WowProblemError}; `false` by default.
   */
  readonly resolveRejected?: boolean;
}

/** The options of a command whose route may name the aggregate id or not. */
export interface CreateCommandOptions extends CommandOptions {
  /**
   * The id of the aggregate to create (decision D176); the service generates
   * one when absent.
   */
  readonly id?: string;
}

/** The lifecycle commands an aggregate may list (decision D113). */
export const LifecycleCommands = {
  DELETE_AGGREGATE: 'wow.lifecycle.DeleteAggregate',
  RESTORE_AGGREGATE: 'wow.lifecycle.RestoreAggregate',
  TRANSFER_OWNER: 'wow.lifecycle.TransferOwner',
  TRANSFER_SPACE: 'wow.lifecycle.TransferSpace',
  APPLY_TAGS: 'wow.lifecycle.ApplyTags',
} as const;

/** A {@link LifecycleCommands} value. */
export type LifecycleCommand =
  (typeof LifecycleCommands)[keyof typeof LifecycleCommands];

/** The body of `wow.lifecycle.TransferOwner`. */
export interface TransferOwner {
  readonly ownerId: string;
}

/** The body of `wow.lifecycle.TransferSpace`. */
export interface TransferSpace {
  readonly spaceId: string;
}

/** The body of `wow.lifecycle.ApplyTags`: the tags, names to values. */
export interface ApplyTags {
  readonly tags: Readonly<Record<string, readonly string[]>>;
}

/**
 * The route segment of a command name, as the server derives it: kebab case,
 * dots kept (`AddCartItem` → `add-cart-item`,
 * `wow.lifecycle.RestoreAggregate` → `wow.lifecycle.restore-aggregate`).
 *
 * @param name - The command's name
 */
export function commandSegment(name: string): string {
  return name
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[_\s]+/g, '-')
    .toLowerCase();
}

/**
 * A new request id (`Wow-Request-Id`): a random UUID. The gateway generates
 * one for a command sent without; generate it first to retry with it however
 * the request fails.
 */
export function newRequestId(): string {
  const crypto = globalThis.crypto;
  if (typeof crypto?.randomUUID === 'function') return crypto.randomUUID();
  // An insecure browser context has no randomUUID: a version 4 UUID by hand.
  const bytes = new Uint8Array(16);
  if (typeof crypto?.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0'));
  return [
    hex.slice(0, 4),
    hex.slice(4, 6),
    hex.slice(6, 8),
    hex.slice(8, 10),
    hex.slice(10, 16),
  ]
    .map(group => group.join(''))
    .join('-');
}

/** Sends with the request id `requestId`, which a failure carries. */
async function withRequestId<T>(
  requestId: string,
  sending: () => Promise<T>,
): Promise<T> {
  try {
    return await sending();
  } catch (error) {
    rememberRequestId(error, requestId);
    throw error;
  }
}

function commandHeaders(
  options: CommandOptions,
  requestId: string,
  accept?: string,
): Record<string, string | undefined> {
  const wait = options.wait;
  return {
    [WowHeaders.REQUEST_ID]: requestId,
    [WowHeaders.WAIT]:
      wait?.plan === undefined ? undefined : formatWaitPlan(wait.plan),
    [WowHeaders.WAIT_TIMEOUT]:
      wait?.timeout === undefined ? undefined : formatWaitTimeout(wait.timeout),
    [WowHeaders.IF_MATCH]:
      options.expectedVersion === undefined
        ? undefined
        : `"${options.expectedVersion}"`,
    Accept: accept,
  };
}

/**
 * Sends commands to the command routes of a Wow service
 * (`POST /{context}/{aggregate}/{id}/{command}`, and without `{id}` for a
 * command that may create its aggregate). Generated aggregate clients build
 * on it; it serves any route too.
 *
 * A command that did not succeed rejects with a {@link WowProblemError}
 * holding its result, unless the options resolve it. Every command carries a
 * request id, the options' or a generated one, and whatever it rejects with
 * carries that id ({@link requestIdOf}).
 */
export class CommandGateway {
  /** @param client - How the service is reached */
  constructor(readonly client: WowClientOptions = {}) {}

  /**
   * Sends a command and answers the result its wait ends with: 200, or 202
   * for a plan that ends at `accepted`.
   *
   * @param path - The route, `{id}` filled from `id`
   * @param body - The command's body; `{}` when absent
   * @param options - Headers, wait and the aggregate id
   */
  async send<R = Record<string, unknown>>(
    path: string,
    body?: object,
    options: CreateCommandOptions = {},
  ): Promise<CommandResult<R>> {
    const requestId = options.requestId ?? newRequestId();
    try {
      return await withRequestId(requestId, async () =>
        json<CommandResult<R>>(await this.post(path, body, options, requestId)),
      );
    } catch (error) {
      if (
        options.resolveRejected &&
        error instanceof WowProblemError &&
        error.result !== undefined
      ) {
        return error.result as CommandResult<R>;
      }
      throw error;
    }
  }

  /**
   * Sends a command with `Accept: text/event-stream` and answers one result
   * per hop as the wait advances. An admission refusal rejects with its
   * problem; a rejection after admission is the stream's last result.
   *
   * @param path - The route, `{id}` filled from `id`
   * @param body - The command's body; `{}` when absent
   * @param options - Headers, wait and the aggregate id
   */
  async stream<R = Record<string, unknown>>(
    path: string,
    body?: object,
    options: CreateCommandOptions = {},
  ): Promise<CommandResultStream<R>> {
    const requestId = options.requestId ?? newRequestId();
    return withRequestId(requestId, async () =>
      commandResults<R>(
        await this.post(path, body, options, requestId, 'text/event-stream'),
      ),
    );
  }

  /**
   * Sends a lifecycle command an aggregate lists: `DELETE
   * /{context}/{aggregate}/{id}` for `DeleteAggregate`, `POST
   * …/{id}/wow.lifecycle.<kebab>` for the others.
   *
   * @param aggregatePath - `/{context}/{aggregate}`
   * @param id - The aggregate's id
   * @param command - The lifecycle command
   * @param body - Its body, for the commands that have one
   * @param options - Headers and wait
   */
  async lifecycle<R = Record<string, unknown>>(
    aggregatePath: string,
    id: string,
    command: LifecycleCommand,
    body?: TransferOwner | TransferSpace | ApplyTags,
    options: CommandOptions = {},
  ): Promise<CommandResult<R>> {
    const base = `${aggregatePath}/${encodeURIComponent(id)}`;
    if (command === LifecycleCommands.DELETE_AGGREGATE) {
      const requestId = options.requestId ?? newRequestId();
      return withRequestId(requestId, async () => {
        const response = await send(this.client, {
          method: 'DELETE',
          path: base,
          headers: commandHeaders(options, requestId),
          options,
        });
        return json<CommandResult<R>>(response);
      });
    }
    return this.send<R>(
      `${base}/${commandSegment(command)}`,
      body ?? {},
      options,
    );
  }

  private post(
    path: string,
    body: object | undefined,
    options: CreateCommandOptions,
    requestId: string,
    accept?: string,
  ): Promise<Response> {
    return send(this.client, {
      method: 'POST',
      path: path.includes('{id}')
        ? expandPath(path, { id: options.id ?? '' })
        : path,
      headers: commandHeaders(options, requestId, accept),
      body: body ?? {},
      ...(options.bodyNumbers === undefined
        ? {}
        : { bodyNumbers: options.bodyNumbers }),
      options,
    });
  }
}
