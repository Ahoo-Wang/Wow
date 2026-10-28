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
  json,
  type RequestOptions,
  send,
  type TargetScope,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
import type { AggregateType, CommandResult, Hop } from './wire.js';
import {
  checkManifest,
  fetchManifest,
  type Manifest,
  type RouteManifest,
} from './manifest.js';
import { rememberRequestId, WowProblemError } from './problem.js';
import { commandResults, type CommandResultStream } from './streams.js';
import { formatWaitPlan, formatWaitTimeout, wait as hop } from './wait.js';

/**
 * One command a {@link CommandGateway} sends, and how: the TypeScript
 * `CommandRequest` of Kotlin's `me.ahoo.wow.api.client`, with the same
 * fields. Kotlin's `command` is the command object, whose class names it;
 * here `command` is the command's name and `body` its payload, as the wire
 * carries them.
 *
 * - `command`: the full type name, `context.aggregate.Command`, which names
 *   the aggregate type too; or a system command (`wow.*`, a lifecycle command
 *   of {@link LifecycleCommands} among them), which needs `type`.
 * - `routes`: the command's routes as its Manifest lists them; a generated
 *   client sets them. Without them, the gateway finds them in its Manifest.
 * - `id`: the aggregate's id. Absent when the command names no aggregate id:
 *   a creation, whose id the server assigns, or an `@Unaddressed` command; the
 *   route without `{id}` then sends it.
 * - `targetTenant`, `targetScope`: the tenant and scope a caller allowed to
 *   act across scopes acts in (`Wow-Scope`); the caller's own when absent.
 * - `wait` (`Wow-Wait`) and its `timeout` in milliseconds
 *   (`Wow-Wait-Timeout`); the server's default plan when absent.
 * - `requestId` (`Wow-Request-Id`) and `expectedVersion` (`If-Match`).
 */
export interface CommandRequest extends Omit<RequestOptions, 'scope'> {
  /** The command's full type name, or a system command's. */
  readonly command: string;
  /**
   * The command's routes as its Manifest lists them, `{id}` the aggregate id:
   * the one with `{id}` sends a request with an `id`, the one without a
   * request without. Without them, the gateway finds them in its Manifest.
   */
  readonly routes?: readonly RouteManifest[];
  /** The command's body; `{}` when absent. */
  readonly body?: object;
  /** The aggregate's id; absent for a creation or an unaddressed command. */
  readonly id?: string;
  /**
   * The aggregate type, which `command` names unless it is a system command:
   * then it is required.
   */
  readonly type?: AggregateType;
  /** The tenant to act in, for a caller allowed to (`Wow-Scope`). */
  readonly targetTenant?: string;
  /** The owner and space to act in, for a caller allowed to (`Wow-Scope`). */
  readonly targetScope?: Omit<TargetScope, 'tenantId'>;
  /**
   * Not a command's: a query's `Wow-Scope`. A command names the scope it acts
   * in with `targetTenant` and `targetScope`, so a query's options spread into
   * a command's fail to compile rather than drop their scope.
   */
  readonly scope?: never;
  /** The hops to wait for, or the `Wow-Wait` value. */
  readonly wait?: readonly Hop[] | string;
  /** How long to wait, in milliseconds. */
  readonly timeout?: number;
  /**
   * The request id (`Wow-Request-Id`): a retry with the same one is
   * deduplicated, answered as the first commit's duplicate. When absent, the
   * gateway generates one ({@link newRequestId}), which a failure carries
   * ({@link requestIdOf}). A request sent again without one is another
   * request: for an application-level retry, set it (or keep the one the
   * failure or the first result carries) and send that request again.
   */
  readonly requestId?: string;
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

/**
 * The options of a generated client's command method: a {@link CommandRequest}
 * without what the method fixes, its command, routes, body and type.
 */
export type CommandOptions = Omit<
  CommandRequest,
  'command' | 'routes' | 'body' | 'type' | 'id'
>;

/** The options of a command that may create its aggregate, with its id or not. */
export interface CreateCommandOptions extends CommandOptions {
  /**
   * The id of the aggregate to create (decision D176); the service generates
   * one when absent.
   */
  readonly id?: string;
}

/** The lifecycle commands an aggregate may list. */
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

/** How long {@link CommandGateway.send} waits for the command to be accepted, without a timeout of its own. */
const ACCEPT_TIMEOUT = 10_000;

/** The prefix of the system commands' names, the lifecycle commands' among them. */
const SYSTEM_PREFIX = 'wow.';

/** The aggregate id of a route's path. */
const ID = '{id}';

/** Whether `plan` waits for acceptance alone. */
function acceptedOnly(plan: readonly Hop[] | string): boolean {
  const spelled = formatWaitPlan(plan);
  return spelled === 'accepted' || /^accepted\([^)]*\)$/.test(spelled);
}

/** The route of `request` among `routes`: its method and path, the id in it. */
function routeOf(
  request: CommandRequest,
  routes: readonly RouteManifest[],
): { method: 'POST' | 'DELETE'; path: string } {
  const id = request.id;
  const route = routes.find(
    item => item.path.includes(ID) === (id !== undefined),
  );
  if (route === undefined) {
    throw new TypeError(
      id === undefined
        ? `${request.command} has no route without an aggregate id: set the request's id.`
        : `${request.command} has no route to an aggregate by its id: send it without an id.`,
    );
  }
  const method = route.method;
  if (method !== 'POST' && method !== 'DELETE') {
    throw new TypeError(
      `${request.command} is routed by ${method}: a command is sent by POST or DELETE.`,
    );
  }
  return {
    method,
    path:
      id === undefined
        ? route.path
        : route.path.replace(ID, encodeURIComponent(id)),
  };
}

/** The aggregate type a full type name `context.aggregate.Command` names. */
function typeNamed(command: string): AggregateType {
  const parts = command.split('.');
  if (parts.length < 3 || parts.some(part => part === '')) {
    throw new TypeError(
      `${command} is not a full type name, context.aggregate.Command: name it so, or set the request's type.`,
    );
  }
  return { context: parts[0], name: parts[1] };
}

function commandHeaders(
  request: CommandRequest,
  plan: readonly Hop[] | string | undefined,
  requestId: string,
  accept?: string,
): Record<string, string | undefined> {
  return {
    [WowHeaders.REQUEST_ID]: requestId,
    [WowHeaders.WAIT]: plan === undefined ? undefined : formatWaitPlan(plan),
    [WowHeaders.WAIT_TIMEOUT]:
      request.timeout === undefined
        ? undefined
        : formatWaitTimeout(request.timeout),
    [WowHeaders.IF_MATCH]:
      request.expectedVersion === undefined
        ? undefined
        : `"${request.expectedVersion}"`,
    Accept: accept,
  };
}

/** The `Wow-Scope` a request targets, when it targets one. */
function targetOf(request: CommandRequest): TargetScope | undefined {
  if (request.targetTenant === undefined && request.targetScope === undefined) {
    return undefined;
  }
  return { tenantId: request.targetTenant, ...request.targetScope };
}

/**
 * Sends commands to the command routes of a Wow service as its Manifest lists
 * them (`POST /{context}/{aggregate}/{id}/{command}`, the route without
 * `{id}` for a request that names none, `DELETE /{context}/{aggregate}/{id}`
 * for `DeleteAggregate`): the request's own routes, else those of the
 * Manifest the gateway is given, else of the service's, fetched once
 * (`GET /wow/manifest`). As Kotlin's `CommandClient` does, {@link send} answers
 * once the command is accepted, {@link wait} with the result its wait plan
 * ends with, {@link stream} with one result per hop. Generated aggregate
 * clients build on it.
 *
 * A command that did not succeed rejects with a {@link WowProblemError}
 * holding its result, unless the request resolves it. Every command carries a
 * request id, the request's or a generated one, and whatever it rejects with
 * carries that id ({@link requestIdOf}).
 */
export class CommandGateway {
  private manifest: Promise<Manifest> | undefined;

  /**
   * @param client - How the service is reached
   * @param manifest - The service's Manifest, whose routes send a request
   *   without its own; fetched once when first needed when absent
   */
  constructor(
    readonly client: WowClientOptions = {},
    manifest?: Manifest,
  ) {
    this.manifest =
      manifest === undefined
        ? undefined
        : Promise.resolve(checkManifest(manifest));
  }

  /**
   * Sends a command and answers once it is accepted (202), within the
   * request's timeout (10 seconds without one), as Kotlin's
   * `CommandClient.send`. A request whose `wait` waits for more rejects with a
   * `TypeError` before it is sent: {@link wait} answers with that plan's result.
   *
   * @param request - The command and how to send it
   */
  async send<R = Record<string, unknown>>(
    request: CommandRequest,
  ): Promise<CommandResult<R>> {
    if (request.wait !== undefined && !acceptedOnly(request.wait)) {
      throw new TypeError(
        `send answers on acceptance: use wait() for the plan ${formatWaitPlan(request.wait)}.`,
      );
    }
    return this.exchange<R>(request, [hop.accepted()], {
      timeout: request.timeout ?? ACCEPT_TIMEOUT,
    });
  }

  /**
   * Sends a command and answers the result its wait plan ends with: 200, or
   * 202 for a plan that ends at `accepted`. Without a plan, the server's
   * default: the snapshot, or the commit where the aggregate keeps no
   * snapshot (decision D69).
   *
   * @param request - The command and how to send it
   */
  wait<R = Record<string, unknown>>(
    request: CommandRequest,
  ): Promise<CommandResult<R>> {
    return this.exchange<R>(request, request.wait, {});
  }

  /**
   * Sends a command with `Accept: text/event-stream` and answers one result
   * per hop as the wait advances. An admission refusal rejects with its
   * problem; a rejection after admission is the stream's last result.
   *
   * @param request - The command and how to send it
   */
  async stream<R = Record<string, unknown>>(
    request: CommandRequest,
  ): Promise<CommandResultStream<R>> {
    const requestId = request.requestId ?? newRequestId();
    return withRequestId(requestId, async () =>
      commandResults<R>(
        await this.post(request, request.wait, requestId, 'text/event-stream'),
      ),
    );
  }

  private async exchange<R>(
    request: CommandRequest,
    plan: readonly Hop[] | string | undefined,
    override: { timeout?: number },
  ): Promise<CommandResult<R>> {
    const requestId = request.requestId ?? newRequestId();
    const sent = { ...request, ...override };
    try {
      return await withRequestId(requestId, async () =>
        json<CommandResult<R>>(await this.post(sent, plan, requestId)),
      );
    } catch (error) {
      if (
        request.resolveRejected &&
        error instanceof WowProblemError &&
        error.result !== undefined
      ) {
        return error.result as CommandResult<R>;
      }
      throw error;
    }
  }

  private async post(
    request: CommandRequest,
    plan: readonly Hop[] | string | undefined,
    requestId: string,
    accept?: string,
  ): Promise<Response> {
    const route = routeOf(
      request,
      request.routes ?? (await this.routesOf(request)),
    );
    const scope = targetOf(request);
    return send(this.client, {
      method: route.method,
      path: route.path,
      headers: commandHeaders(request, plan, requestId, accept),
      ...(route.method === 'DELETE' ? {} : { body: request.body ?? {} }),
      ...(request.bodyNumbers === undefined
        ? {}
        : { bodyNumbers: request.bodyNumbers }),
      options: {
        ...(scope === undefined ? {} : { scope }),
        ...(request.space === undefined ? {} : { space: request.space }),
        ...(request.headers === undefined ? {} : { headers: request.headers }),
        ...(request.signal === undefined ? {} : { signal: request.signal }),
      },
    });
  }

  /**
   * The routes of `request`'s command in the Manifest: a domain command's
   * among its aggregate's commands, by name or former name; a system
   * command's among its type's, else among its context's (a job's).
   */
  private async routesOf(
    request: CommandRequest,
  ): Promise<readonly RouteManifest[]> {
    const command = request.command;
    const system = command.startsWith(SYSTEM_PREFIX);
    const type = request.type ?? (system ? undefined : typeNamed(command));
    if (type === undefined) {
      throw new TypeError(
        `${command} is a system command every aggregate takes: set the request's type.`,
      );
    }
    const prefix = `${type.context}.${type.name}.`;
    const name = command.startsWith(prefix)
      ? command.slice(prefix.length)
      : command;
    const context = (await this.manifestOf()).contexts.find(
      item => item.name === type.context,
    );
    const declared =
      context?.aggregates
        ?.find(aggregate => aggregate.name === type.name)
        ?.commands?.find(
          item => item.name === name || item.aliases?.includes(name) === true,
        ) ??
      (system
        ? context?.commands?.find(item => item.name === name)
        : undefined);
    if (declared?.routes === undefined) {
      throw new TypeError(
        `The Manifest lists no command ${name} of ${type.context}.${type.name}: set the request's routes.`,
      );
    }
    return declared.routes;
  }

  /** The Manifest given, or the service's, fetched once (again after a failure). */
  private manifestOf(): Promise<Manifest> {
    this.manifest ??= fetchManifest(this.client).catch((error: unknown) => {
      this.manifest = undefined;
      throw error;
    });
    return this.manifest;
  }
}
