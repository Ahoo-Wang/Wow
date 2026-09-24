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

import type { RequestHeaders, UrlParams } from '@ahoo-wang/fetcher';
import { CommandHeaders } from './commandHeaders.js';
import { type UrlPathParams } from '../types/index.js';
import type { ParameterRequest } from '@ahoo-wang/fetcher-decorator';
import { type CommandBody, CommandStage } from './types.js';

/** A command stage, as the enum member or as its name. */
export type CommandStageName = CommandStage | `${CommandStage}`;

/**
 * The headers of a command request.
 *
 * Every command header is optional and typed by what the server parses:
 * a stage is a {@link CommandStage}, a version or a timeout is an integer,
 * `Local-First` is a boolean. Any other header (`Authorization`, a
 * `Command-Header-*` extension, …) is still allowed as a string. Build these
 * with {@link commandHeaders} and {@link waitStrategy} rather than by hand.
 *
 * @example
 * ```typescript
 * const headers: CommandRequestHeaders = {
 *   ...commandHeaders({ ownerId: 'user-1', requestId }),
 *   ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
 * };
 * ```
 */
export interface CommandRequestHeaders extends RequestHeaders {
  /** Tenant of the command, when the route has no tenant path segment */
  [CommandHeaders.TENANT_ID]?: string;
  /** Owner of the command, when the route has no owner path segment */
  [CommandHeaders.OWNER_ID]?: string;
  /** Space of the command, `Wow-Space-Id` */
  [CommandHeaders.SPACE_ID]?: string;
  /** Aggregate the command targets, when the route has no id path segment */
  [CommandHeaders.AGGREGATE_ID]?: string;
  /** The aggregate version the command expects, an integer */
  [CommandHeaders.AGGREGATE_VERSION]?: `${number}`;
  /** How long the server waits, in whole milliseconds */
  [CommandHeaders.WAIT_TIME_OUT]?: `${number}`;
  /** The stage to wait for; `PROCESSED` when absent */
  [CommandHeaders.WAIT_STAGE]?: CommandStageName;
  /** The bounded context the wait stage refers to */
  [CommandHeaders.WAIT_CONTEXT]?: string;
  /** The processor the wait stage refers to */
  [CommandHeaders.WAIT_PROCESSOR]?: string;
  /** The function the wait stage refers to */
  [CommandHeaders.WAIT_FUNCTION]?: string;
  /** The stage of the wait-chain tail; only with a `SAGA_HANDLED` wait stage */
  [CommandHeaders.WAIT_TAIL_STAGE]?: CommandStageName;
  /** The bounded context of the wait-chain tail */
  [CommandHeaders.WAIT_TAIL_CONTEXT]?: string;
  /** The processor of the wait-chain tail */
  [CommandHeaders.WAIT_TAIL_PROCESSOR]?: string;
  /** The function of the wait-chain tail */
  [CommandHeaders.WAIT_TAIL_FUNCTION]?: string;
  /** Idempotency key of the command */
  [CommandHeaders.REQUEST_ID]?: string;
  /** Whether the server may process the command locally */
  [CommandHeaders.LOCAL_FIRST]?: 'true' | 'false';
  /** Bounded context of the aggregate, for `/wow/command/send` */
  [CommandHeaders.COMMAND_AGGREGATE_CONTEXT]?: string;
  /** Name of the aggregate, for `/wow/command/send` */
  [CommandHeaders.COMMAND_AGGREGATE_NAME]?: string;
  /** Fully qualified command type, for `/wow/command/send` */
  [CommandHeaders.COMMAND_TYPE]?: string;
}

/** Who a command is for; each option sets one command header. */
export interface CommandHeaderOptions {
  tenantId?: string;
  ownerId?: string;
  spaceId?: string;
  aggregateId?: string;
  /** The aggregate version the command expects: a non-negative integer. */
  aggregateVersion?: number;
  /** Idempotency key; the server refuses a repeated one. */
  requestId?: string;
  localFirst?: boolean;
}

/** The processing a wait stage refers to; blank means any. */
export interface WaitFunction {
  context?: string;
  processor?: string;
  function?: string;
}

/** Wait for one stage of the command. */
export interface WaitStageOptions extends WaitFunction {
  /** The stage to wait for. The server waits for `PROCESSED` when absent. */
  stage?: CommandStageName;
  /** How long the server waits, in whole milliseconds. */
  timeoutMs?: number;
  tail?: never;
}

/**
 * Wait for a saga to handle the command's events, and then for the command
 * that saga sends to reach `tail.stage` — Wow's wait chain.
 */
export interface WaitChainOptions extends WaitFunction {
  stage: CommandStage.SAGA_HANDLED | 'SAGA_HANDLED';
  tail: WaitFunction & { stage: CommandStageName };
  /** How long the server waits, in whole milliseconds. */
  timeoutMs?: number;
}

/** What {@link waitStrategy} takes: one stage, or a wait chain. */
export type WaitStrategyOptions = WaitStageOptions | WaitChainOptions;

const STAGES: ReadonlySet<string> = new Set(Object.values(CommandStage));

function stageName(label: string, stage: string): CommandStageName {
  if (!STAGES.has(stage)) {
    throw new TypeError(
      `${label} must be one of ${[...STAGES].join(', ')}, got [${stage}].`,
    );
  }
  return stage as CommandStageName;
}

function integer(label: string, value: number, minimum: number): `${number}` {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new TypeError(
      `${label} must be an integer of at least ${minimum}, got [${value}].`,
    );
  }
  return `${value}`;
}

function put(
  headers: CommandRequestHeaders,
  name: keyof CommandRequestHeaders,
  value: string | undefined,
): void {
  if (value !== undefined) headers[name] = value as never;
}

/**
 * Builds the headers that say who a command is for. Options left out send
 * no header.
 *
 * @throws TypeError when `aggregateVersion` is not a non-negative integer.
 *
 * @example
 * ```typescript
 * await commandClient.send({
 *   path: 'owner/{ownerId}/cart/add_cart_item',
 *   method: 'POST',
 *   headers: commandHeaders({ spaceId: 'store-1', requestId: crypto.randomUUID() }),
 *   body: { productId: 'p-1', quantity: 1 },
 * });
 * ```
 */
export function commandHeaders(
  options: CommandHeaderOptions,
): CommandRequestHeaders {
  const headers: CommandRequestHeaders = {};
  put(headers, CommandHeaders.TENANT_ID, options.tenantId);
  put(headers, CommandHeaders.OWNER_ID, options.ownerId);
  put(headers, CommandHeaders.SPACE_ID, options.spaceId);
  put(headers, CommandHeaders.AGGREGATE_ID, options.aggregateId);
  if (options.aggregateVersion !== undefined) {
    headers[CommandHeaders.AGGREGATE_VERSION] = integer(
      'aggregateVersion',
      options.aggregateVersion,
      0,
    );
  }
  put(headers, CommandHeaders.REQUEST_ID, options.requestId);
  if (options.localFirst !== undefined) {
    headers[CommandHeaders.LOCAL_FIRST] = options.localFirst ? 'true' : 'false';
  }
  return headers;
}

/**
 * Builds the headers that say how long, and for what, the server waits
 * before it answers a command — Kotlin's `WaitingFor`. Options left out send
 * no header, and the server's defaults apply: `PROCESSED` in the command's own
 * bounded context.
 *
 * @throws TypeError when a stage is not a `CommandStage`, when `timeoutMs` is
 *   not a positive integer, or when a `tail` is given with a stage other than
 *   `SAGA_HANDLED` (the server ignores the tail then).
 *
 * @example
 * ```typescript
 * // Answer once the snapshot is written, or after five seconds.
 * waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 5_000 });
 *
 * // Answer once the saga's command has been processed.
 * waitStrategy({
 *   stage: 'SAGA_HANDLED',
 *   processor: 'TransferSaga',
 *   tail: { stage: 'PROCESSED', context: 'account' },
 * });
 * ```
 */
export function waitStrategy(
  options: WaitStrategyOptions,
): CommandRequestHeaders {
  const headers: CommandRequestHeaders = {};
  if (options.stage !== undefined) {
    headers[CommandHeaders.WAIT_STAGE] = stageName('stage', options.stage);
  }
  put(headers, CommandHeaders.WAIT_CONTEXT, options.context);
  put(headers, CommandHeaders.WAIT_PROCESSOR, options.processor);
  put(headers, CommandHeaders.WAIT_FUNCTION, options.function);
  if (options.tail !== undefined) {
    if (options.stage !== CommandStage.SAGA_HANDLED) {
      throw new TypeError('A wait tail needs the SAGA_HANDLED stage.');
    }
    headers[CommandHeaders.WAIT_TAIL_STAGE] = stageName(
      'tail.stage',
      options.tail.stage,
    );
    put(headers, CommandHeaders.WAIT_TAIL_CONTEXT, options.tail.context);
    put(headers, CommandHeaders.WAIT_TAIL_PROCESSOR, options.tail.processor);
    put(headers, CommandHeaders.WAIT_TAIL_FUNCTION, options.tail.function);
  }
  if (options.timeoutMs !== undefined) {
    headers[CommandHeaders.WAIT_TIME_OUT] = integer(
      'timeoutMs',
      options.timeoutMs,
      1,
    );
  }
  return headers;
}

export interface CommandUrlParams extends Omit<UrlParams, 'path' | 'query'> {
  path?: UrlPathParams;
}

/**
 * A command request: the path of the command route, its URL parameters, its
 * typed headers and its body.
 */
export interface CommandRequest<
  C extends object = object,
> extends ParameterRequest<CommandBody<C>> {
  urlParams?: CommandUrlParams;
  headers?: CommandRequestHeaders;
  /**
   * The body of the command request.
   */
  body?: CommandBody<C>;
}
