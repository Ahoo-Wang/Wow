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

/**
 * The wire model of a Wow service: what its routes answer, as the server
 * writes it (`wow-api`'s `CommandResult`, the edge's state views, the query
 * records). Types and wire values only.
 */

/** An aggregate type: its bounded context and its name. */
export interface AggregateType {
  readonly context: string;
  readonly name: string;
  /**
   * Which numbers of its states, events and commands are read and written
   * as their exact text (review R4-Q, Q-9): the `int64`, `bigint` and
   * `decimal` fields of its Manifest, as wow-generator writes them.
   */
  readonly textNumbers?: AggregateTextNumbers;
}

/** The numbers of an aggregate type kept as their exact text ({@link TextNumbers}). */
export interface AggregateTextNumbers {
  /** Those of its state. */
  readonly state?: TextNumbers;
  /** Those of each event's body, by the event's name as the server sends it. */
  readonly events?: Readonly<Record<string, TextNumbers>>;
  /** Those of each command's body, by the command's name. */
  readonly commands?: Readonly<Record<string, TextNumbers>>;
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
 * Who sent a command: the opaque id of the authenticated
 * subject, its own tenant, and whether it acted in another tenant or scope.
 * Resolve the id to a display name in the application, never from Wow.
 */
export interface Operator {
  readonly id: string;
  readonly tenantId: string;
  readonly crossScope: boolean;
}

/** How far a command has come. */
export const Stage = {
  /** The command passed admission. */
  ACCEPTED: 'accepted',
  /** The command's events are appended to the log. */
  COMMITTED: 'committed',
  /** A named subscription has handled the commit. */
  HANDLED: 'handled',
} as const;

/** A {@link Stage} value. */
export type Stage = (typeof Stage)[keyof typeof Stage];

/**
 * One hop of a wait chain: a stage, in a context (the
 * command's own when absent), of a subscription when the stage is
 * `handled`. The snapshot is the subscription named `snapshot`.
 */
export interface Hop {
  /**
   * The stage; a server newer than this client may answer one it does not
   * list.
   */
  readonly stage: Stage | (string & {});
  readonly context?: string | null;
  readonly subscription?: string | null;
}

/** The kind of function that produced a result. */
export type FunctionKind =
  | 'command'
  | 'project'
  | 'react'
  | 'saga'
  | 'snapshot'
  | 'reservation'
  | 'timers';

/** Who produced a result: a function of `kind` in `context`. */
export interface FunctionInfo {
  /**
   * The kind; a server newer than this client may answer one it does not
   * list.
   */
  readonly kind: FunctionKind | (string & {});
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
export type CommandResultStatus = 'succeeded' | 'rejected' | 'failed';

/**
 * The command a request named before a transformer turned it into the one
 * that ran.
 */
export interface TransformedFrom {
  /** The full type name, `context.aggregate.Command`. */
  readonly command: string;
  readonly aggregateId?: string | null;
}

/**
 * The one outward result of a command: what it reached at one
 * hop of its wait chain. A command route answers the last one; its event
 * stream sends one per hop.
 *
 * A chain can end before its plan does (decision D212): a hop, such as a
 * saga's that sent no command (a declined payment, say), leaves nothing to
 * reach the later hops. Its result is then the last one, at that hop with its
 * own status, `chainEnded` and the hops not reached in `pending`; a server
 * before D212 sends neither, so read them as `false` and empty when absent.
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
  /**
   * The status; a server newer than this client may answer one it does not
   * list, which did not succeed as far as this client knows.
   */
  readonly status: CommandResultStatus | (string & {});
  /** The aggregate's version after the commit, or the unchanged one. */
  readonly version?: number | null;
  /** The request was already committed: it was not decided again. */
  readonly duplicate: boolean;
  /** Set when `status` is `rejected` or `failed`. */
  readonly error?: ErrorInfo | null;
  readonly results: R;
  /** On a saga's hop, the ids of the commands it derived. */
  readonly derived: readonly string[];
  /** When it was produced, in epoch milliseconds. */
  readonly signalTime: number;
  readonly transformedFrom?: TransformedFrom | null;
  /**
   * The chain ended at this hop before its plan did (decision D212): the
   * hops in `pending` will not come.
   */
  readonly chainEnded?: boolean;
  /** When `chainEnded`, the hops of the plan not reached, in order. */
  readonly pending?: readonly Hop[];
}

/** The tags of an aggregate: names to values. */
export type Tags = Readonly<Record<string, readonly string[]>>;

/**
 * An aggregate's state at a version, as
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
 * An event as Wow reads it now: under its current name, upgraded to its latest revision. The `events` query gives
 * every stored event so, however old its record, and leaves out the events its aggregate retired, so a generated
 * `<Aggregate>Event` union, with {@link SystemEvent}, covers each event it returns.
 *
 * @typeParam N - The event's name.
 * @typeParam B - The event's body.
 */
export interface EventView<N extends string = string, B = unknown> {
  readonly name: N;
  readonly revision: number;
  readonly body: B;
}

/** The body of `wow.lifecycle.OwnerTransferred`: the aggregate's new owner. */
export interface OwnerTransferred {
  readonly ownerId: string;
}

/** The body of `wow.lifecycle.SpaceTransferred`: the aggregate's new space. */
export interface SpaceTransferred {
  readonly spaceId: string;
}

/** The body of `wow.lifecycle.TagsApplied`: the aggregate's tags now. */
export interface TagsApplied {
  readonly tags: Tags;
}

/**
 * The body of `wow.ScheduleSet`: the aggregate set its
 * schedule `key`, to send `target` the command of type `commandType`
 * (`command` its JSON text) as `rule` says.
 */
export interface ScheduleSet {
  readonly key: string;
  readonly rule: ScheduleRule;
  readonly target: AggregateRef;
  readonly commandType: string;
  readonly command: string;
}

/** The body of `wow.ScheduleCancelled`. */
export interface ScheduleCancelled {
  readonly key: string;
}

/**
 * The system events the kernel gives every aggregate:
 * the lifecycle events and the schedule events. A state trace and an event
 * stream carry them beside the aggregate's own events, so the event union
 * `wow-generator` writes for an aggregate includes this one.
 */
export type SystemEvent =
  | EventView<'wow.lifecycle.AggregateDeleted', Readonly<Record<string, never>>>
  | EventView<
      'wow.lifecycle.AggregateRestored',
      Readonly<Record<string, never>>
    >
  | EventView<'wow.lifecycle.OwnerTransferred', OwnerTransferred>
  | EventView<'wow.lifecycle.SpaceTransferred', SpaceTransferred>
  | EventView<'wow.lifecycle.TagsApplied', TagsApplied>
  | EventView<'wow.ScheduleSet', ScheduleSet>
  | EventView<'wow.ScheduleCancelled', ScheduleCancelled>;

/**
 * One commit of a state trace: the events of a version and the
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
 * A record of the `snapshot` query model: the
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

/** A schedule rule of a snapshot's `schedules`. */
export interface ScheduleRule {
  /** The rule's type: `at`, `after`, `cron`, … */
  readonly type: string;
  readonly [property: string]: unknown;
}

/**
 * A record of the `events` query model: one commit of one
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
  /** Null when the stream has none (`Scope.NONE`). */
  readonly ownerId?: string | null;
  readonly spaceId?: string | null;
  /** Null for an anonymous caller (decision D163). */
  readonly operator?: Operator | null;
  readonly events: readonly E[];
}

/**
 * The fields of an event stream record a query names: its
 * system fields and `events`, the array an `elementMatch` filter and an
 * aggregation's `elements` reach an event through, by the event's own
 * `name`, `revision` and `body.*`.
 */
export type EventStreamField =
  | 'aggregateId'
  | 'tenantId'
  | 'version'
  | 'commitTime'
  | 'commandId'
  | 'requestId'
  | 'commandType'
  | 'ownerId'
  | 'spaceId'
  | 'operator'
  | 'operator.id'
  | 'operator.tenantId'
  | 'operator.crossScope'
  | 'events';

/** How an aggregate belongs to its callers. */
export type Ownership = 'owner' | 'tenant';

/** One dataset of `GET /wow/dataset`. */
export interface DatasetEntry {
  /** `{context}.{dataset}`. */
  readonly name: string;
  readonly context: string;
  readonly dataset: string;
  readonly title?: string | null;
  /** The path of its query route. */
  readonly path: string;
}
