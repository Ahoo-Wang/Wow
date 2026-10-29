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
  json,
  type RequestOptions,
  send,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
import { eventNumbers, type TextNumbers } from './exactJson.js';
import { FrameworkErrorCodes, WowProblemError } from './problem.js';
import type {
  AggregateType,
  CommittedEvent,
  StateTrace,
  Snapshot,
} from './wire.js';

/** Which state a read asks for: the latest, at a version or as of a time. */
export interface StateReadOptions extends RequestOptions {
  /** The committed state at this version. */
  readonly version?: number;
  /** The committed state as of this instant (ISO-8601, or a `Date`). */
  readonly at?: string | Date;
  /**
   * The latest state at this version or later (review R4-Q, Q-13: read your
   * writes), such as the `version` of a command's result: a lagging snapshot is
   * caught up with the log. Not with `version` or `at`. One not reached within
   * the server's wait rejects with 409 `wow.query.min-version-not-reached`,
   * whose `version` is the current one.
   */
  readonly minVersion?: number;
  /**
   * The entity tag of the state already held, as a read answered it
   * (`etag`): a 304 then answers `notModified`. The tag is the version and a
   * digest of the body (`3-<digest>`), so it changes whenever what is read
   * does, not only with the version.
   */
  readonly ifNoneMatch?: string;
}

/**
 * A state read: the snapshot and its entity tag, or `notModified` when the
 * state held is current, with its tag and the version the tag names.
 */
export type StateReadResult<S> =
  | {
      readonly notModified: false;
      readonly snapshot: Snapshot<S>;
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
 */
export class StateClient<S = unknown, E extends CommittedEvent = CommittedEvent> {
  /**
   * @param type - The aggregate type
   * @param client - How the service is reached
   */
  constructor(
    readonly type: AggregateType,
    readonly client: WowClientOptions = {},
  ) {}

  /** The state the options ask for. */
  read(
    id: string,
    options: StateReadOptions = {},
  ): Promise<StateReadResult<S>> {
    return this.readAt(id, options);
  }

  /** The latest state, or the one the options ask for, without a cache. */
  get(
    id: string,
    options: Omit<StateReadOptions, 'ifNoneMatch'> = {},
  ): Promise<Snapshot<S>> {
    return this.getAt(id, options);
  }

  /**
   * The latest state, or the one the options ask for, or `undefined` when the
   * aggregate does not exist (404 `wow.aggregate.not-found`), as Kotlin's
   * `SnapshotQueries.state` answers null. Any other 404, such as a route that
   * does not exist, rejects.
   */
  find(
    id: string,
    options: Omit<StateReadOptions, 'ifNoneMatch'> = {},
  ): Promise<Snapshot<S> | undefined> {
    return this.findAt(id, options);
  }

  /** The commits from `from` to `to`, each with its events and the state after it. */
  trace(
    id: string,
    options: StateTraceOptions = {},
  ): Promise<StateTrace<S, E>> {
    return this.traceAt(id, options);
  }

  /**
   * {@link StateClient.read} of the aggregate `id`, or by no id of the
   * caller's own ({@link OwnStateClient}).
   */
  protected async readAt(
    id: string | undefined,
    options: StateReadOptions,
  ): Promise<StateReadResult<S>> {
    const at =
      options.at instanceof Date ? options.at.toISOString() : options.at;
    const response = await send(this.client, {
      method: 'GET',
      path: this.statePath(id),
      query: { version: options.version, at, minVersion: options.minVersion },
      headers: {
        [WowHeaders.IF_NONE_MATCH]:
          options.ifNoneMatch === undefined
            ? undefined
            : `"${options.ifNoneMatch}"`,
      },
      options,
      notModified: true,
    });
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
      snapshot: await json<Snapshot<S>>(response, this.stateNumbers),
      etag: entityTag(response),
    };
  }

  /** {@link StateClient.get} of the aggregate `id`, or of the caller's own. */
  protected async getAt(
    id: string | undefined,
    options: Omit<StateReadOptions, 'ifNoneMatch'>,
  ): Promise<Snapshot<S>> {
    const result = await this.readAt(id, options);
    if (result.notModified) {
      throw new Error('A read without If-None-Match was answered 304.');
    }
    return result.snapshot;
  }

  /** {@link StateClient.find} of the aggregate `id`, or of the caller's own. */
  protected async findAt(
    id: string | undefined,
    options: Omit<StateReadOptions, 'ifNoneMatch'>,
  ): Promise<Snapshot<S> | undefined> {
    try {
      return await this.getAt(id, options);
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
  protected async traceAt(
    id: string | undefined,
    options: StateTraceOptions,
  ): Promise<StateTrace<S, E>> {
    return json<StateTrace<S, E>>(
      await send(this.client, {
        method: 'GET',
        path: `${this.statePath(id)}/trace`,
        query: { from: options.from, to: options.to, limit: options.limit },
        options,
      }),
      this.traceNumbers,
    );
  }

  /** The exact numbers of a state read: its state's. */
  private get stateNumbers(): TextNumbers | undefined {
    const state = this.type.textNumbers?.state;
    return state === undefined ? undefined : { state };
  }

  /** The exact numbers of a trace: each commit's state and events. */
  private get traceNumbers(): TextNumbers | undefined {
    const state = this.type.textNumbers?.state;
    const events = eventNumbers(this.type);
    if (state === undefined && events === undefined) return undefined;
    return {
      commits: {
        '*': {
          ...(state === undefined ? {} : { state }),
          ...(events === undefined ? {} : { events: { '*': events } }),
        },
      },
    };
  }

  private statePath(id: string | undefined): string {
    const base = `/${this.type.context}/${this.type.name}`;
    return id === undefined
      ? `${base}/state`
      : `${base}/${encodeURIComponent(id)}/state`;
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
 */
export class OwnStateClient<
  S = unknown,
  E extends CommittedEvent = CommittedEvent,
> extends StateClient<S, E> {
  /** {@link StateClient.read} of the caller's own aggregate. */
  readOwn(options: StateReadOptions = {}): Promise<StateReadResult<S>> {
    return this.readAt(undefined, options);
  }

  /** {@link StateClient.get} of the caller's own aggregate. */
  getOwn(
    options: Omit<StateReadOptions, 'ifNoneMatch'> = {},
  ): Promise<Snapshot<S>> {
    return this.getAt(undefined, options);
  }

  /**
   * {@link StateClient.find} of the caller's own aggregate: `undefined` until
   * the caller's first command creates it.
   */
  findOwn(
    options: Omit<StateReadOptions, 'ifNoneMatch'> = {},
  ): Promise<Snapshot<S> | undefined> {
    return this.findAt(undefined, options);
  }

  /** {@link StateClient.trace} of the caller's own aggregate. */
  traceOwn(options: StateTraceOptions = {}): Promise<StateTrace<S, E>> {
    return this.traceAt(undefined, options);
  }
}
