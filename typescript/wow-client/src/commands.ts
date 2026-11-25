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
  ANSWER_GRACE_MS,
  AnswerBound,
  LONGEST_WAIT_MS,
  unknownOutcome,
} from './answerBound.js';
import {
  type Answer,
  pathSegment,
  type RequestOptions,
  send,
  type TargetScope,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
import {
  type AggregateType,
  type CommandResult,
  CommandResultStatus,
  type RefusedResult,
  Stage,
} from './wire.js';
import {
  ManifestSource,
  type RouteManifest,
  type RoutingCommand,
  type RoutingManifest,
} from './manifest.js';
import {
  CommandRejectedError,
  FrameworkErrorCodes,
  rememberRequestId,
  WowProblemError,
} from './problem.js';
import { commandResults, type CommandResultStream } from './streams.js';
import { formatWaitPlan, formatWaitTimeout, WaitPlan } from './wait.js';

/**
 * One command a {@link CommandClient} sends, and how: the TypeScript
 * `CommandRequest` of Kotlin's `me.ahoo.wow.api.client`, with the same
 * fields. Kotlin's `command` is the command object, whose class names it;
 * here `command` is the command's name and `body` its payload, as the wire
 * carries them.
 *
 * - `command`: the full type name, `context.aggregate.Command`, which names
 *   the aggregate type too; or a system command (`wow.*`, a lifecycle command
 *   of {@link LifecycleCommands} among them), which needs `type`.
 * - `aggregateId`: the aggregate's id, as Kotlin's
 *   `CommandRequest.aggregateId` names it beside the command's and the
 *   request's ids. Absent when the command names no aggregate id: a creation,
 *   whose id the server assigns, an `@Unaddressed` command, or any command of
 *   an aggregate whose id is its owner's (the Manifest's
 *   `idSource: "owner"`), which goes to the caller's own; the route without
 *   `{id}` then sends it. A command whose body names its aggregate (the
 *   Manifest's `idProperty`) goes by that route too, whether `aggregateId` is
 *   given or not: a given one is the one its body names, or is written into a
 *   body that names none.
 * - `targetTenant`, `targetScope`: the tenant and scope a caller allowed to
 *   act across scopes acts in (`Wow-Scope`); the caller's own when absent.
 * - `wait` (`Wow-Wait`), a {@link WaitPlan}, and `timeout` in milliseconds
 *   (`Wow-Wait-Timeout`), which overrides the plan's: the plan the command
 *   waits with is `wait` within `timeout`, the server's default plan when
 *   only a timeout is given, and the server's default without either.
 * - `requestId` (`Wow-Request-Id`) and `expectedVersion` (`If-Match`).
 */
export interface CommandRequest extends Omit<RequestOptions, 'scope'> {
  /** The command's full type name, or a system command's. */
  readonly command: string;
  /** The command's body; `{}` when absent. */
  readonly body?: object;
  /**
   * The aggregate's id; absent for a creation, an unaddressed command or the
   * caller's own aggregate of one whose id is its owner's.
   */
  readonly aggregateId?: string;
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
  /** How far to wait for the command (`Wow-Wait`). */
  readonly wait?: WaitPlan;
  /** How long to wait, in milliseconds, over the plan's own timeout. */
  readonly timeout?: number;
  /**
   * The request id (`Wow-Request-Id`): a retry with the same one is
   * deduplicated, answered as the first commit's duplicate. When absent, the
   * client generates one ({@link newRequestId}), which a failure carries
   * ({@link requestIdOf}). A request sent again without one is another
   * request: for an application-level retry, set it (or keep the one the
   * failure or the first result carries) and send that request again.
   */
  readonly requestId?: string;
  /** The version the aggregate must be at (`If-Match`). */
  readonly expectedVersion?: number;
}

/** How a {@link CommandClient} sends, beside how it reaches the service. */
export interface CommandClientOptions {
  /**
   * The Manifest whose routes send each command: a whole Manifest, or the
   * routing part a generated client carries (`manifest.ts`). Without it the
   * client fetches the service's once, when it first sends
   * (`GET /wow/manifest`), and again after a failed fetch.
   */
  readonly manifest?: RoutingManifest;
  /**
   * The body numbers each command writes as their exact text, unquoted JSON
   * numbers (decision D122), by the command's full type name: a generated
   * client sets them for commands with `int64`, `bigint` or `decimal` fields,
   * which it types as `string`.
   */
  readonly textNumbers?: Readonly<Record<string, TextNumbers>>;
}

/**
 * The options of a generated client's command method: a {@link CommandRequest}
 * without what the method fixes, its command, body and type.
 */
export type CommandOptions = Omit<
  CommandRequest,
  'command' | 'body' | 'type' | 'aggregateId'
>;

/** The options of a command that may create its aggregate, with its id or not. */
export interface CreateCommandOptions extends CommandOptions {
  /**
   * The id of the aggregate to create (decision D176); the service generates
   * one when absent.
   */
  readonly aggregateId?: string;
}

/** The lifecycle commands an aggregate may list. */
export const LifecycleCommands = {
  DELETE_AGGREGATE: 'wow.lifecycle.DeleteAggregate',
  RESTORE_AGGREGATE: 'wow.lifecycle.RestoreAggregate',
  TRANSFER_OWNER: 'wow.lifecycle.TransferOwner',
  TRANSFER_SPACE: 'wow.lifecycle.TransferSpace',
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

/**
 * A new request id (`Wow-Request-Id`): a UUIDv7 (RFC 9562), the Unix
 * milliseconds it was made in, then random bits. The client generates one
 * for a command sent without; generate it first to retry with it however the
 * request fails.
 *
 * The time comes first so that ids sort by when they were made, as the
 * Kotlin client's do: the service keeps a unique index on the request id,
 * and ids that arrive in order insert at its end rather than at a random
 * page. Ids made within one millisecond share its prefix and order among
 * themselves at random, which keeps them on the same page without a counter
 * shared across calls.
 *
 * The random bits come from `crypto.getRandomValues`, which a page served
 * over plain HTTP has too (it lacks only `randomUUID`), else from
 * `Math.random`.
 */
export function newRequestId(): string {
  const bytes = new Uint8Array(16);
  const crypto = globalThis.crypto;
  if (typeof crypto?.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  // The 48-bit timestamp, big-endian, in the first six bytes.
  let time = Date.now();
  for (let index = 5; index >= 0; index--) {
    bytes[index] = time % 256;
    time = Math.floor(time / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
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

/** The prefix of the system commands' names, the lifecycle commands' among them. */
const SYSTEM_PREFIX = 'wow.';

/** The aggregate id of a route's path. */
const ID = '{id}';

/** Whether `plan` waits for acceptance alone. */
function acceptedOnly(plan: WaitPlan): boolean {
  return plan.hops.length === 1 && plan.hops[0].stage === Stage.ACCEPTED;
}

/**
 * The plan `request` waits with, as Kotlin's `CommandRequest.plan`: its
 * `wait` within its `timeout`, the default plan when only a timeout is given.
 */
function planOf(request: CommandRequest): WaitPlan | undefined {
  if (request.timeout === undefined) return request.wait;
  return (request.wait ?? WaitPlan.DEFAULT).within(request.timeout);
}

/**
 * The route of `request` among `routes`, by `id` (the request's
 * `aggregateId`, none for a command whose body names its aggregate): its
 * method and path, the id in it.
 * An empty id is refused before anything is sent: it would leave the path's
 * segment empty (`/<context>/<aggregate>//…`), a route no service has.
 */
function routeOf(
  request: CommandRequest,
  routes: readonly RouteManifest[],
  id: string | undefined,
): { method: 'POST' | 'DELETE'; path: string } {
  const route = routes.find(
    item => item.path.includes(ID) === (id !== undefined),
  );
  if (route === undefined) {
    throw new TypeError(
      id === undefined
        ? `${request.command} has no route without an aggregate id: set the request's aggregateId.`
        : `${request.command} has no route to an aggregate by its id: send it without an aggregateId.`,
    );
  }
  if (id === '') {
    throw new TypeError(
      `The path ${route.path} of ${request.command} needs id.`,
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
        : route.path.replace(ID, pathSegment(id, 'The aggregate id')),
  };
}

/**
 * The body `request` sends, `{}` when it has none. Of a command whose body
 * names its aggregate in `idProperty`, the route has no id: the request's
 * `aggregateId`, when it names one, is the body's too, written into a body
 * that names none.
 *
 * @throws TypeError when the body names another aggregate than the request
 */
function bodyOf(
  request: CommandRequest,
  idProperty: string | undefined,
): object {
  const body = request.body ?? {};
  const id = request.aggregateId;
  if (idProperty === undefined || id === undefined) return body;
  const named: unknown = (body as Record<string, unknown>)[idProperty];
  if (named === undefined || named === null) {
    return { ...body, [idProperty]: id };
  }
  if (named !== id) {
    throw new TypeError(
      `The request names the aggregate ${id}, but the body of ${request.command} names ${String(named)}: send it without an aggregateId.`,
    );
  }
  return body;
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

/**
 * How long the whole answer of a request waiting with `plan` may take: the
 * wait it states, its plan's timeout, else the service's default wait
 * (`defaultWaitMs`, a minute when absent), and a grace after it.
 */
function boundOf(plan: WaitPlan | undefined, client: WowClientOptions): number {
  return (
    (plan?.timeout ?? client.defaultWaitMs ?? LONGEST_WAIT_MS) + ANSWER_GRACE_MS
  );
}

function commandHeaders(
  request: CommandRequest,
  plan: WaitPlan | undefined,
  requestId: string,
  accept?: string,
): Record<string, string | undefined> {
  return {
    [WowHeaders.REQUEST_ID]: requestId,
    // The default plan is the service's to pick: it sends no plan.
    [WowHeaders.WAIT]:
      plan === undefined || plan.isDefault ? undefined : formatWaitPlan(plan),
    // Every plan states its timeout, as Kotlin's client does (10 seconds
    // unless `within` or the request's `timeout` says otherwise); a request
    // with no plan leaves the service its default.
    [WowHeaders.WAIT_TIMEOUT]:
      plan === undefined ? undefined : formatWaitTimeout(plan.timeout),
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
 * for `DeleteAggregate`): the Manifest the client is given, else the
 * service's, fetched once (`GET /wow/manifest`). The TypeScript
 * `CommandClient` of Kotlin's `me.ahoo.wow.api.client`: {@link send} answers
 * once the command is accepted, {@link wait} with the result its wait plan
 * ends with, {@link stream} with one result per hop. Generated aggregate
 * clients build on it.
 *
 * `send` and `wait` resolve only a result that succeeded. A rejected one,
 * by the aggregate or at admission, rejects with a
 * {@link CommandRejectedError}; anything else that did not succeed with a
 * {@link WowProblemError} (the table on {@link CommandRejectedError} says
 * which). Every command carries a request id, the request's or a generated
 * one, and whatever it rejects with carries that id ({@link requestIdOf}).
 *
 * A command's whole answer, its head, its body and a stream's every event,
 * is bounded by the wait it states and 5 seconds more, as the Kotlin
 * client's is. Every plan states its timeout (`Wow-Wait-Timeout`): the
 * plan's own, 10 seconds unless `within` says otherwise, or the request's
 * `timeout` over it. A request with neither `wait` nor `timeout` states
 * none, so its wait is the service's default, bounded by the client's
 * {@link WowClientOptions.defaultWaitMs} (a minute when absent, the longest
 * default a service takes unless its `wow.wait.max-timeout` is raised) and
 * the same grace. The service answers `wow.wait.timeout` at the wait's end,
 * so a node that has not answered by then never will: past the bound the
 * request is aborted, its connection closed, and the call rejects with
 * `wow.command.unknown`. So does an answer lost or unreadable after its head:
 * the command may have committed, and a retry with its request id is
 * answered as the duplicate it is. An abort of the request's own `signal`
 * rejects with the abort's reason.
 */
export class CommandClient {
  private readonly manifest: ManifestSource;
  private readonly textNumbers: Readonly<Record<string, TextNumbers>>;

  /**
   * @param options - How the service is reached
   * @param contract - The service's Manifest, and the commands' exact numbers
   */
  constructor(
    private readonly options: WowClientOptions = {},
    contract: CommandClientOptions = {},
  ) {
    this.manifest = new ManifestSource(options, contract.manifest);
    this.textNumbers = contract.textNumbers ?? {};
  }

  /**
   * Sends a command and answers once it is accepted (202), within the
   * request's timeout (10 seconds without one), stated as its wait, and the
   * grace after it, as Kotlin's `CommandClient.send`. A request whose `wait` waits
   * for more rejects with a `TypeError` before it is sent: {@link wait}
   * answers with that plan's result.
   *
   * @param request - The command and how to send it
   */
  async send<R = Record<string, unknown>>(
    request: CommandRequest,
  ): Promise<CommandResult<R>> {
    const plan = planOf(request);
    if (plan !== undefined && !plan.isDefault && !acceptedOnly(plan)) {
      throw new TypeError(
        `send answers on acceptance: use wait() for the plan ${formatWaitPlan(plan)}.`,
      );
    }
    return this.exchange<R>(
      request,
      WaitPlan.ACCEPTED.within(request.timeout ?? WaitPlan.DEFAULT_TIMEOUT),
    );
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
    return this.exchange<R>(request, planOf(request));
  }

  /**
   * Sends a command with `Accept: text/event-stream` and answers one result
   * per hop as the wait advances, whatever its status. An admission refusal
   * rejects with its problem; a rejection after admission is the stream's
   * last result.
   *
   * @param request - The command and how to send it
   */
  async stream<R = Record<string, unknown>>(
    request: CommandRequest,
  ): Promise<CommandResultStream<R>> {
    const requestId = request.requestId ?? newRequestId();
    return withRequestId(requestId, async () => {
      const { answer, bound } = await this.post(
        request,
        planOf(request),
        requestId,
        'text/event-stream',
      );
      return commandResults<R>(answer, requestId, bound);
    });
  }

  private async exchange<R>(
    request: CommandRequest,
    plan: WaitPlan | undefined,
  ): Promise<CommandResult<R>> {
    const requestId = request.requestId ?? newRequestId();
    return withRequestId(requestId, async () => {
      let sent: { answer: Answer; bound: AnswerBound };
      try {
        sent = await this.post(request, plan, requestId);
      } catch (error) {
        if (
          error instanceof WowProblemError &&
          error.result?.status === CommandResultStatus.REJECTED
        ) {
          throw new CommandRejectedError(error.result, {
            problem: error.problem,
            cause: error,
          });
        }
        throw error;
      }
      const { answer, bound } = sent;
      let result: CommandResult<R>;
      try {
        result = await answer.json<CommandResult<R>>();
      } catch (error) {
        // Past the head: the command was taken, and its answer is lost.
        if (bound.abandoned) throw error;
        throw unknownOutcome(
          bound.passed
            ? `The command's answer did not come within ${bound.ms} ms, its wait and a grace: whether it committed is unknown.`
            : "The command's answer was lost, or could not be read, after it began: whether it committed is unknown.",
          requestId,
          error,
        );
      } finally {
        bound.clear();
      }
      return settled(result);
    });
  }

  /**
   * Sends `request` and answers its head, with the bound its whole answer
   * is held to, which the reader of its body clears.
   */
  private async post(
    request: CommandRequest,
    plan: WaitPlan | undefined,
    requestId: string,
    accept?: string,
  ): Promise<{ answer: Answer; bound: AnswerBound }> {
    const declared = await this.commandOf(request);
    const idProperty = declared.idProperty;
    const route = routeOf(
      request,
      declared.routes,
      idProperty === undefined ? request.aggregateId : undefined,
    );
    const scope = targetOf(request);
    const bodyNumbers = this.textNumbers[request.command];
    const body =
      route.method === 'DELETE' ? {} : { body: bodyOf(request, idProperty) };
    const bound = new AnswerBound(boundOf(plan, this.options), request.signal);
    try {
      const answer = await send(this.options, {
        method: route.method,
        path: route.path,
        headers: commandHeaders(request, plan, requestId, accept),
        ...body,
        // A result's numbers are the framework's; its `results` are the
        // service's own, which the Manifest gives no schema.
        numbers: { body: bodyNumbers },
        options: {
          ...(scope === undefined ? {} : { scope }),
          ...(request.space === undefined ? {} : { space: request.space }),
          ...(request.headers === undefined
            ? {}
            : { headers: request.headers }),
          ...('signal' in bound.sending ? bound.sending : {}),
        },
        ...('abortController' in bound.sending ? bound.sending : {}),
      });
      return { answer, bound };
    } catch (error) {
      bound.clear();
      // No head within the bound: a node that took the request and went
      // silent. A problem the service did answer is its own.
      if (bound.passed && !(error instanceof WowProblemError)) {
        throw unknownOutcome(
          `The command was not answered within ${bound.ms} ms, its wait and a grace: whether it committed is unknown.`,
          requestId,
          error,
        );
      }
      throw error;
    }
  }

  /**
   * `request`'s command as the Manifest lists it, with its routes: a domain
   * command among its aggregate's commands, by name or former name, a former
   * name listed alone or as a full type name; a system
   * command among its type's, else among its context's (a job's).
   */
  private async commandOf(request: CommandRequest): Promise<RoutingCommand> {
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
    const context = (await this.manifest.read(request.signal)).contexts.find(
      item => item.name === type.context,
    );
    const declared =
      context?.aggregates
        ?.find(aggregate => aggregate.name === type.name)
        ?.commands?.find(
          item =>
            item.name === name ||
            item.aliases?.includes(name) === true ||
            // A former name the Manifest lists as a full type name, as the
            // Kotlin client matches it (`ManifestPeers.declared`).
            item.aliases?.includes(command) === true,
        ) ??
      (system
        ? context?.commands?.find(item => item.name === name)
        : undefined);
    if (declared?.routes === undefined) {
      throw new TypeError(
        `The Manifest lists no command ${name} of ${type.context}.${type.name}.`,
      );
    }
    return declared;
  }
}

/**
 * The result a command was answered with, when it succeeded; else the error
 * that says why it did not, as Kotlin's `HttpCommandClient` settles one: a
 * rejected result rejects with a {@link CommandRejectedError}, a status this
 * client does not know (a newer service's) with a {@link WowProblemError}.
 */
function settled<R>(result: CommandResult<R>): CommandResult<R> {
  if (result.status === CommandResultStatus.REJECTED) {
    throw new CommandRejectedError(result as RefusedResult);
  }
  if (!KNOWN_STATUSES.has(result.status)) {
    throw new WowProblemError({
      type: `urn:wow:error:${FrameworkErrorCodes.COMMAND_FAILED}`,
      title: 'Unknown command status',
      status: 200,
      code: FrameworkErrorCodes.COMMAND_FAILED,
      detail: `The command was answered with the status ${result.status}, which this client does not know: the service is newer than this client, so upgrade it.`,
      result: result as RefusedResult,
    });
  }
  return result;
}

const KNOWN_STATUSES: ReadonlySet<string> = new Set(
  Object.values(CommandResultStatus),
);
