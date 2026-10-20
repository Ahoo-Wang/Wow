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
import type {
  AggregateType,
  EventView,
  StateTrace,
  StateView,
} from './wire.js';

/** Which state a read asks for: the latest, at a version or as of a time. */
export interface StateReadOptions extends RequestOptions {
  /** The committed state at this version. */
  readonly version?: number;
  /** The committed state as of this instant (ISO-8601, or a `Date`). */
  readonly at?: string | Date;
  /**
   * The entity tag of the state already held, as a read answered it
   * (`etag`): a 304 then answers `notModified`. The tag is the version and a
   * digest of the body (`3-<digest>`), so it changes whenever what is read
   * does, not only with the version.
   */
  readonly ifNoneMatch?: string;
}

/**
 * A state read: the view and its entity tag, or `notModified` when the state
 * held is current, with its tag and the version the tag names.
 */
export type StateReadResult<S> =
  | {
      readonly notModified: false;
      readonly view: StateView<S>;
      readonly etag?: string;
    }
  | {
      readonly notModified: true;
      readonly version: number;
      readonly etag: string;
    };

/** Which commits a trace reads (decision D40). */
export interface StateTraceOptions extends RequestOptions {
  /** The first version; 1 when absent. */
  readonly from?: number;
  /** The last version; the latest when absent. */
  readonly to?: number;
  /** The most commits, at most 1000; 100 when absent. */
  readonly limit?: number;
}

/**
 * Reads an aggregate type's states (decisions D40, D112):
 * `GET /{context}/{aggregate}/{id}/state` with `?version=` or `?at=`, and
 * its trace, `…/state/trace`. A state that does not exist rejects with 404
 * `wow.aggregate.not-found`.
 *
 * @typeParam S - The state's type.
 * @typeParam E - The events' type of a trace.
 */
export class StateClient<S = unknown, E extends EventView = EventView> {
  /**
   * @param type - The aggregate type
   * @param client - How the service is reached
   */
  constructor(
    readonly type: AggregateType,
    readonly client: WowClientOptions = {},
  ) {}

  /** The state the options ask for. */
  async read(
    id: string,
    options: StateReadOptions = {},
  ): Promise<StateReadResult<S>> {
    const at =
      options.at instanceof Date ? options.at.toISOString() : options.at;
    const response = await send(this.client, {
      method: 'GET',
      path: this.statePath(id),
      query: { version: options.version, at },
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
      view: await json<StateView<S>>(response),
      etag: entityTag(response),
    };
  }

  /** The latest state, or the one the options ask for, without a cache. */
  async get(
    id: string,
    options: Omit<StateReadOptions, 'ifNoneMatch'> = {},
  ): Promise<StateView<S>> {
    const result = await this.read(id, options);
    if (result.notModified) {
      throw new Error('A read without If-None-Match was answered 304.');
    }
    return result.view;
  }

  /** The commits from `from` to `to`, each with its events and the state after it. */
  async trace(
    id: string,
    options: StateTraceOptions = {},
  ): Promise<StateTrace<S, E>> {
    return json<StateTrace<S, E>>(
      await send(this.client, {
        method: 'GET',
        path: `${this.statePath(id)}/trace`,
        query: { from: options.from, to: options.to, limit: options.limit },
        options,
      }),
    );
  }

  private statePath(id: string): string {
    return `/${this.type.context}/${this.type.name}/${encodeURIComponent(id)}/state`;
  }
}
