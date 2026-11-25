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

import {
  entityTag,
  pathSegment,
  type RequestOptions,
  send,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
import { eventNumbers, recordNumbers, type TextNumbers } from './exactJson.js';
import { FrameworkErrorCodes, WowProblemError } from './problem.js';
import type {
  AccessValues,
  AggregateType,
  CommittedEvent,
  StateTrace,
  Snapshot,
} from './wire.js';

/**
 * Which state a read asks for, one way at most: the latest; the committed
 * state at a `version`; the committed state as of a time, `at`; or the
 * latest at `minVersion` or later. With the entity tag of the state held,
 * `ifNoneMatch`, and the request's options.
 */
export type StateReadOptions = RequestOptions & {
  /**
   * The entity tag of the state already held, as a read answered it
   * (`etag`): a 304 then answers `notModified`. The tag is the version and a
   * digest of the body (`3-<digest>`), so it changes whenever what is read
   * does, not only with the version.
   */
  readonly ifNoneMatch?: string;
} & (
    | {
        readonly version?: undefined;
        readonly at?: undefined;
        readonly minVersion?: undefined;
      }
    | {
        /** The committed state at this version. */
        readonly version: number;
        readonly at?: undefined;
        readonly minVersion?: undefined;
      }
    | {
        readonly version?: undefined;
        /** The committed state as of this instant (ISO-8601, or a `Date`). */
        readonly at: string | Date;
        readonly minVersion?: undefined;
      }
    | {
        readonly version?: undefined;
        readonly at?: undefined;
        /**
         * The latest state at this version or later (review R4-Q, Q-13: read
         * your writes), such as the `version` of a command's result: a
         * lagging snapshot is caught up with the log. One not reached within
         * the server's wait rejects with 409
         * `wow.query.min-version-not-reached`, whose `version` is the
         * current one.
         */
        readonly minVersion: number;
      }
  );

/**
 * A state read: the snapshot and its entity tag, or `notModified` when the
 * state held is current, with its tag and the version the tag names.
 */
export type StateReadResult<S, A = AccessValues> =
  | {
      readonly notModified: false;
      readonly snapshot: Snapshot<S, A>;
      readonly etag?: string;
    }
  | {
      readonly notModified: true;
      readonly version: number;
      readonly etag: string;
    };

/** Which commits a trace reads. */
export interface StateTraceOptions extends RequestOptions {
  /** The first version; 1 when absent. */
  readonly from?: number;
  /** The last version; the latest when absent. */
  readonly to?: number;
  /** The most commits, at most 1000; 100 when absent. */
  readonly limit?: number;
}

/** Where the states of an aggregate type are read: the type, and how its service is reached. */
interface StateRoute {
  readonly type: AggregateType;
  readonly options: WowClientOptions;
}

/**
 * Reads an aggregate type's states:
 * `GET /{context}/{aggregate}/{id}/state` with `?version=`, `?at=` or `?minVersion=`, and
 * its trace, `…/state/trace`; {@link OwnStateClient} reads the caller's own
 * of a type whose id is its owner's. A state that does not exist rejects with 404
 * `wow.aggregate.not-found`, except through {@link StateClient.find}, which
 * answers `undefined`.
 *
 * @typeParam S - The state's type.
 * @typeParam E - The events' type of a trace.
 * @typeParam A - The aggregate's access attributes.
 */
export class StateClient<
  S = unknown,
  E extends CommittedEvent = CommittedEvent,
  A = AccessValues,
> {
  private readonly route: StateRoute;

  /**
   * @param type - The aggregate type
   * @param options - How the service is reached
   */
  constructor(
    readonly type: AggregateType,
    options: WowClientOptions = {},
  ) {
    this.route = { type, options };
  }

  /** The state the options ask for. */
  read(
    id: string,
    options: StateReadOptions = {},
  ): Promise<StateReadResult<S, A>> {
    return readState(this.route, id, options);
  }

  /** The latest state, or the one the options ask for, without a cache. */
  get(
    id: string,
    options: StateReadOptions & { readonly ifNoneMatch?: undefined } = {},
  ): Promise<Snapshot<S, A>> {
    return getState(this.route, id, options);
  }

  /**
   * The latest state, or the one the options ask for, or `undefined` when the
   * aggregate does not exist (404 `wow.aggregate.not-found`), as Kotlin's
   * `SnapshotQueries.state` answers null. Any other 404, such as a route that
   * does not exist, rejects.
   */
  find(
    id: string,
    options: StateReadOptions & { readonly ifNoneMatch?: undefined } = {},
  ): Promise<Snapshot<S, A> | undefined> {
    return findState(this.route, id, options);
  }

  /** The commits from `from` to `to`, each with its events and the state after it. */
  trace(
    id: string,
    options: StateTraceOptions = {},
  ): Promise<StateTrace<S, E, A>> {
    return traceState(this.route, id, options);
  }
}

/**
 * Reads the states of an aggregate type whose id is its owner's
 * (`@AggregateRoot(idSource = OWNER)`, the Manifest's `idSource: "owner"`):
 * by id as {@link StateClient} does, for a caller with no owner, and the
 * caller's own by no id, `GET /{context}/{aggregate}/state` and
 * `…/state/trace`, its id the owner the caller reads as. An unauthenticated
 * caller is refused with 401 `wow.auth.unauthenticated`, one with no owner
 * with 400 `wow.query.malformed`.
 *
 * @typeParam S - The state's type.
 * @typeParam E - The events' type of a trace.
 * @typeParam A - The aggregate's access attributes.
 */
export class OwnStateClient<
  S = unknown,
  E extends CommittedEvent = CommittedEvent,
  A = AccessValues,
> extends StateClient<S, E, A> {
  private readonly own: StateRoute;

  /**
   * @param type - The aggregate type
   * @param options - How the service is reached
   */
  constructor(type: AggregateType, options: WowClientOptions = {}) {
    super(type, options);
    this.own = { type, options };
  }

  /** {@link StateClient.read} of the caller's own aggregate. */
  readOwn(options: StateReadOptions = {}): Promise<StateReadResult<S, A>> {
    return readState(this.own, undefined, options);
  }

  /** {@link StateClient.get} of the caller's own aggregate. */
  getOwn(
    options: StateReadOptions & { readonly ifNoneMatch?: undefined } = {},
  ): Promise<Snapshot<S, A>> {
    return getState(this.own, undefined, options);
  }

  /**
   * {@link StateClient.find} of the caller's own aggregate: `undefined` until
   * the caller's first command creates it.
   */
  findOwn(
    options: StateReadOptions & { readonly ifNoneMatch?: undefined } = {},
  ): Promise<Snapshot<S, A> | undefined> {
    return findState(this.own, undefined, options);
  }

  /** {@link StateClient.trace} of the caller's own aggregate. */
  traceOwn(options: StateTraceOptions = {}): Promise<StateTrace<S, E, A>> {
    return traceState(this.own, undefined, options);
  }
}

/**
 * {@link StateClient.read} of the aggregate `id` of `route`, or by no id of
 * the caller's own ({@link OwnStateClient}).
 */
async function readState<S, A>(
  route: StateRoute,
  id: string | undefined,
  options: StateReadOptions,
): Promise<StateReadResult<S, A>> {
  const at = options.at instanceof Date ? options.at.toISOString() : options.at;
  const answer = await send(route.options, {
    method: 'GET',
    path: statePath(route.type, id),
    query: { version: options.version, at, minVersion: options.minVersion },
    headers: {
      [WowHeaders.IF_NONE_MATCH]:
        options.ifNoneMatch === undefined
          ? undefined
          : `"${options.ifNoneMatch}"`,
    },
    numbers: { answer: stateNumbers(route.type) },
    options,
    notModified: true,
  });
  const response = answer.response;
  if (response.status === 304) {
    const etag = entityTag(response) ?? options.ifNoneMatch ?? '';
    return {
      notModified: true,
      version: Number.parseInt(etag, 10),
      etag,
    };
  }
  return {
    notModified: false,
    snapshot: await answer.json<Snapshot<S, A>>(),
    etag: entityTag(response),
  };
}

/** {@link StateClient.get} of the aggregate `id`, or of the caller's own. */
async function getState<S, A>(
  route: StateRoute,
  id: string | undefined,
  options: StateReadOptions & { readonly ifNoneMatch?: undefined },
): Promise<Snapshot<S, A>> {
  const result = await readState<S, A>(route, id, options);
  if (result.notModified) {
    throw new Error('A read without If-None-Match was answered 304.');
  }
  return result.snapshot;
}

/** {@link StateClient.find} of the aggregate `id`, or of the caller's own. */
async function findState<S, A>(
  route: StateRoute,
  id: string | undefined,
  options: StateReadOptions & { readonly ifNoneMatch?: undefined },
): Promise<Snapshot<S, A> | undefined> {
  try {
    return await getState<S, A>(route, id, options);
  } catch (error) {
    if (
      error instanceof WowProblemError &&
      error.status === 404 &&
      error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND
    ) {
      return undefined;
    }
    throw error;
  }
}

/** {@link StateClient.trace} of the aggregate `id`, or of the caller's own. */
async function traceState<S, E extends CommittedEvent, A>(
  route: StateRoute,
  id: string | undefined,
  options: StateTraceOptions,
): Promise<StateTrace<S, E, A>> {
  const answer = await send(route.options, {
    method: 'GET',
    path: `${statePath(route.type, id)}/trace`,
    query: { from: options.from, to: options.to, limit: options.limit },
    numbers: { answer: traceNumbers(route.type) },
    options,
  });
  return answer.json<StateTrace<S, E, A>>();
}

/** The exact numbers of a state read: its state's and its access attributes'. */
function stateNumbers(type: AggregateType): TextNumbers | undefined {
  return recordNumbers({
    state: type.textNumbers?.state,
    access: type.textNumbers?.access,
  });
}

/** The exact numbers of a trace: each commit's state, events and access attributes. */
function traceNumbers(type: AggregateType): TextNumbers | undefined {
  const events = eventNumbers(type);
  const commit = recordNumbers({
    state: type.textNumbers?.state,
    events: events === undefined ? undefined : { '*': events },
    access: type.textNumbers?.access,
  });
  return commit === undefined ? undefined : { commits: { '*': commit } };
}

/** The state route of `type`: of the aggregate `id`, or of the caller's own. */
function statePath(type: AggregateType, id: string | undefined): string {
  const base = `/${type.context}/${type.name}`;
  return id === undefined
    ? `${base}/state`
    : `${base}/${pathSegment(id, 'The aggregate id')}/state`;
}
