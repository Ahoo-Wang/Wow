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

/**
 * The wire model of a Wow V10 service: what its routes answer, as the server
 * writes it (`wow-api`'s `CommandResult`, the edge's state views, the query
 * records). Types and wire values only.
 */

/** An aggregate type: its bounded context and its name. */
export interface AggregateType {
  readonly context: string;
  readonly name: string;
}

/** One aggregate: its type, its tenant and its id. */
export interface AggregateRef {
  readonly type: AggregateType;
  readonly tenantId: string;
  readonly id: string;
}

/** The owner and the space an aggregate belongs to, beside its tenant. */
export interface Scope {
  readonly ownerId?: string | null;
  readonly spaceId?: string | null;
}

/**
 * Who sent a command (decision D163): the opaque id of the authenticated
 * subject, its own tenant, and whether it acted in another tenant or scope.
 * Resolve the id to a display name in the application, never from Wow.
 */
export interface Operator {
  readonly id: string;
  readonly tenantId: string;
  readonly crossScope: boolean;
}

/** How far a command has come (decision D61). */
export const Stage = {
  /** The command passed admission. */
  ACCEPTED: 'ACCEPTED',
  /** The command's events are appended to the log. */
  COMMITTED: 'COMMITTED',
  /** A named subscription has handled the commit. */
  HANDLED: 'HANDLED',
} as const;

/** A {@link Stage} value. */
export type Stage = (typeof Stage)[keyof typeof Stage];

/**
 * One hop of a wait chain (decision D62): a stage, in a context (the
 * command's own when absent), of a subscription when the stage is
 * `HANDLED`. The snapshot is the subscription named `snapshot`.
 */
export interface Hop {
  readonly stage: Stage;
  readonly context?: string | null;
  readonly subscription?: string | null;
}

/** The kind of function that produced a result. */
export type FunctionKind =
  | 'COMMAND'
  | 'PROJECT'
  | 'REACT'
  | 'SAGA'
  | 'SNAPSHOT'
  | 'RESERVATION'
  | 'TIMERS';

/** Who produced a result: a function of `kind` in `context`. */
export interface FunctionInfo {
  readonly kind: FunctionKind;
  readonly context: string;
  readonly processor: string;
  readonly name: string;
}

/** A field-level validation error. */
export interface Violation {
  /** The path of the field, or the query part, that broke the rule. */
  readonly path: string;
  /** The rule's code, such as `NotBlank` or `UNKNOWN_FIELD`. */
  readonly code: string;
  /** A human-readable message. */
  readonly message: string;
}

/** Why a command was rejected or failed. */
export interface ErrorInfo {
  /** A framework code (`wow.*`) or the domain's own. */
  readonly code: string;
  readonly message: string;
  readonly violations?: readonly Violation[];
  /**
   * The rejection's structured data (decision D211), which a problem
   * writes as its extension members.
   */
  readonly extensions?: Readonly<Record<string, unknown>>;
}

/** How a command ended at a hop. */
export type CommandResultStatus = 'SUCCEEDED' | 'REJECTED' | 'FAILED';

/**
 * The command a request named before a transformer turned it into the one
 * that ran (decision D182).
 */
export interface TransformedFrom {
  /** The full type name, `context.aggregate.Command`. */
  readonly command: string;
  readonly aggregateId?: string | null;
}

/**
 * The one outward result of a command (design 08 §7): what it reached at one
 * hop of its wait chain. A command route answers the last one; its event
 * stream sends one per hop.
 *
 * @typeParam R - The shape of `results`, what the command's decide function
 * answered with.
 */
export interface CommandResult<R = Record<string, unknown>> {
  readonly format: number;
  readonly commandId: string;
  readonly requestId: string;
  readonly correlationId: string;
  readonly ref: AggregateRef;
  readonly hop: Hop;
  readonly function: FunctionInfo;
  readonly status: CommandResultStatus;
  /** The aggregate's version after the commit, or the unchanged one. */
  readonly version?: number | null;
  /** The request was already committed: it was not decided again. */
  readonly duplicate: boolean;
  /** Set when `status` is `REJECTED` or `FAILED`. */
  readonly error?: ErrorInfo | null;
  readonly results: R;
  /** On a saga's hop, the ids of the commands it derived. */
  readonly derived: readonly string[];
  /** When it was produced, in epoch milliseconds. */
  readonly signalTime: number;
  readonly transformedFrom?: TransformedFrom | null;
}

/** The tags of an aggregate: names to values. */
export type Tags = Readonly<Record<string, readonly string[]>>;

/**
 * An aggregate's state at a version (decision D40), as
 * `GET /{context}/{aggregate}/{id}/state` answers it.
 *
 * @typeParam S - The state's type.
 */
export interface StateView<S = unknown> {
  readonly ref: AggregateRef;
  readonly version: number;
  readonly scope: Scope;
  readonly deleted: boolean;
  readonly tags: Tags;
  readonly state: S;
}

/**
 * An event as Wow reads it now, upgraded to its latest revision.
 *
 * @typeParam N - The event's name.
 * @typeParam B - The event's body.
 */
export interface EventView<N extends string = string, B = unknown> {
  readonly name: N;
  readonly revision: number;
  readonly body: B;
}

/**
 * One commit of a state trace (decision D40): the events of a version and the
 * aggregate after it.
 *
 * @typeParam S - The state's type.
 * @typeParam E - The events' type.
 */
export interface CommitView<S = unknown, E extends EventView = EventView> {
  readonly version: number;
  /** When it was committed, in epoch milliseconds. */
  readonly commitTime: number;
  readonly commandId: string;
  readonly requestId: string;
  readonly commandType: string;
  readonly events: readonly E[];
  readonly scope: Scope;
  readonly deleted: boolean;
  readonly tags: Tags;
  readonly state: S;
  readonly operator?: Operator | null;
}

/**
 * What `GET /{context}/{aggregate}/{id}/state/trace` answers: the aggregate
 * and its commits in version order.
 */
export interface StateTrace<S = unknown, E extends EventView = EventView> {
  readonly ref: AggregateRef;
  readonly commits: readonly CommitView<S, E>[];
}

/**
 * A record of the `snapshot` query model (decisions D128, D163): the
 * aggregate's system fields and its state.
 *
 * @typeParam S - The state's type.
 */
export interface SnapshotRecord<S = unknown> {
  readonly tenantId: string;
  readonly aggregateId: string;
  readonly version: number;
  readonly ownerId: string | null;
  readonly spaceId: string | null;
  readonly deleted: boolean;
  readonly tags: Tags;
  /** The first commit's time, in epoch milliseconds. */
  readonly firstEventTime: number;
  /** The last commit's time, in epoch milliseconds. */
  readonly lastEventTime: number;
  /** Who sent the last command; null for an anonymous caller. */
  readonly operator: Operator | null;
  /** Who created the aggregate; null for an anonymous caller. */
  readonly firstOperator: Operator | null;
  /**
   * The schedules the aggregate keeps (decision D198): each rule, `{type,
   * ...}`, by its key; absent from a server before 6.8c.
   */
  readonly schedules?: Readonly<Record<string, ScheduleRule>>;
  readonly state: S;
}

/**
 * The system fields of a snapshot record a query can filter, sort and
 * project on; a state field is `state.<path>`.
 */
export type SnapshotSystemField =
  | 'tenantId'
  | 'aggregateId'
  | 'version'
  | 'ownerId'
  | 'spaceId'
  | 'deleted'
  | 'tags'
  | 'firstEventTime'
  | 'lastEventTime'
  | 'operator'
  | 'operator.id'
  | 'operator.tenantId'
  | 'operator.crossScope'
  | 'firstOperator'
  | 'firstOperator.id'
  | 'firstOperator.tenantId'
  | 'firstOperator.crossScope'
  | 'schedules';

/** A schedule rule of a snapshot's `schedules` (decision D198). */
export interface ScheduleRule {
  /** The rule's type: `at`, `after`, `cron`, … */
  readonly type: string;
  readonly [property: string]: unknown;
}

/**
 * A record of the `events` query model (decision D131): one commit of one
 * aggregate and its events.
 *
 * @typeParam E - The events' type.
 */
export interface EventStreamRecord<E extends EventView = EventView> {
  readonly aggregateId: string;
  readonly tenantId: string;
  readonly version: number;
  /** When it was committed, in epoch milliseconds. */
  readonly commitTime: number;
  readonly commandId: string;
  readonly requestId: string;
  readonly commandType: string;
  readonly ownerId?: string;
  readonly spaceId?: string;
  readonly operator?: Operator;
  readonly events: readonly E[];
}

/** The fields of an event stream record a query can filter and sort on. */
export type EventStreamField =
  'aggregateId' | 'tenantId' | 'version' | 'commitTime';

/** How an aggregate belongs to its callers (decision D189). */
export type Ownership = 'OWNER' | 'TENANT';

/** One dataset of `GET /wow/dataset` (decision D145). */
export interface DatasetEntry {
  /** `{context}.{dataset}`. */
  readonly name: string;
  readonly context: string;
  readonly dataset: string;
  readonly title?: string | null;
  /** The path of its query route. */
  readonly path: string;
}
