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

import type { AggregationQuery } from './dsl/aggregation/index.js';
import type { CursorPage, CursorQuery } from './dsl/cursorQuery.js';
import type { Projected } from './dsl/projection.js';
import type { QueryModelDescriptor } from './dsl/descriptor.js';
import type { FilterCapable } from './dsl/filter/index.js';
import type {
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
} from './dsl/queryable.js';
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
  DatasetEntry,
  EventStreamField,
  EventStreamRecord,
  EventView,
  SnapshotRecord,
  SnapshotSystemField,
} from './wire.js';
import {
  resumableQueryRows,
  type StreamedRow,
  type StreamResumeOptions,
} from './streams.js';

/** How a list is streamed: the request's options, and how it resumes. */
export interface ListStreamOptions extends RequestOptions {
  /** How the stream resumes (decision D253); `false` never resumes it. */
  readonly resume?: StreamResumeOptions | false;
}

/** How a paged query counts its total (`TotalMode`, decision D150). */
export type TotalMode = 'EXACT' | 'BOUNDED' | 'NONE';

/** A paged query of a query route: a page, and how its total is counted. */
export interface PagedQuery<
  FIELDS extends string = string,
  INCLUDE extends FIELDS = FIELDS,
> extends FilterPagedQuery<FIELDS, INCLUDE> {
  /** `BOUNDED` (the server's default) counts up to the budget's limit. */
  total?: TotalMode;
}

/** A count of a query route (decision D150). */
export interface CountQuery<
  FIELDS extends string = string,
> extends FilterCapable<FIELDS> {
  /** The most matches to count; the budget's default when absent. */
  limit?: number;
}

/** What a count answers: exact below its limit, else the limit itself. */
export interface CountResult {
  readonly count: number;
  readonly exact: boolean;
}

/**
 * A page of a paged query: `total` is exact when `exact`, a lower bound
 * otherwise, and null for `TotalMode.NONE`.
 */
export interface PagedResult<T> {
  readonly total: number | null;
  readonly exact: boolean;
  readonly list: readonly T[];
}

/**
 * A query model's capability descriptor and its version, the `ETag` of
 * `GET …/query`; `notModified` when the version given is still current.
 */
export type DescriptorResult =
  | {
      readonly notModified: false;
      readonly version?: string;
      readonly descriptor: QueryModelDescriptor;
    }
  | { readonly notModified: true; readonly version: string };

/**
 * Queries one query model of a Wow service: `POST {path}` with a body
 * whose `kind` names the operation, `GET {path}` for the capability
 * descriptor (decisions D127, D145). A query whose projection includes
 * literal paths answers records narrowed to them ({@link Projected}, review
 * R4-Q, Q-14).
 *
 * @typeParam R - The record's type.
 * @typeParam FIELDS - The field paths a filter, sort or projection takes.
 */
export class QueryModelClient<R, FIELDS extends string = string> {
  /**
   * @param path - The query route, `/{context}/{aggregate}/{model}/query`
   * @param client - How the service is reached
   */
  constructor(
    readonly path: string,
    readonly client: WowClientOptions = {},
  ) {}

  /** The records that match, at most `limit` (the server's default when absent). */
  async list<INCLUDE extends FIELDS = FIELDS>(
    query: FilterListQuery<FIELDS, INCLUDE>,
    options?: RequestOptions,
  ): Promise<Projected<R, INCLUDE, FIELDS>[]> {
    return json<Projected<R, INCLUDE, FIELDS>[]>(
      await this.post('list', query, options),
    );
  }

  /**
   * The records that match, as server-sent events read one at a time; a
   * failure after the first record errors the stream with its problem. The
   * stream resumes where it stopped (decision D253) when the server ends it
   * to be resumed, when it is cut off and when it fails with a retryable
   * problem: see {@link StreamResumeOptions}. A `limit` counts every record
   * read, resumed or not.
   */
  async listStream<INCLUDE extends FIELDS = FIELDS>(
    query: FilterListQuery<FIELDS, INCLUDE>,
    options?: ListStreamOptions,
  ): Promise<ReadableStream<Projected<R, INCLUDE, FIELDS>>> {
    return (await this.listStreamRows(query, options)).pipeThrough(
      new TransformStream<
        StreamedRow<Projected<R, INCLUDE, FIELDS>>,
        Projected<R, INCLUDE, FIELDS>
      >({
        transform(row, controller) {
          controller.enqueue(row.record);
        },
      }),
    );
  }

  /**
   * The records that match as {@link listStream} reads them, each with its
   * position: keep the last one read to resume the stream later
   * ({@link StreamResumeOptions.lastEventId}), on another page or after a
   * restart.
   */
  async listStreamRows<INCLUDE extends FIELDS = FIELDS>(
    query: FilterListQuery<FIELDS, INCLUDE>,
    options: ListStreamOptions = {},
  ): Promise<ReadableStream<StreamedRow<Projected<R, INCLUDE, FIELDS>>>> {
    const resume =
      options.resume === false ? { maxRetries: 0 } : options.resume;
    const limit = query.limit ?? 0;
    const open = (lastEventId: string | undefined, received: number) => {
      return this.post(
        'list',
        limit > 0 ? { ...query, limit: limit - received } : query,
        options,
        'text/event-stream',
        lastEventId,
      );
    };
    const first = await open(resume?.lastEventId, 0);
    return resumableQueryRows<Projected<R, INCLUDE, FIELDS>>(
      first,
      (lastEventId, received) =>
        limit > 0 && received >= limit
          ? Promise.resolve(undefined)
          : open(lastEventId, received),
      {
        ...resume,
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
    );
  }

  /** One page of the records that match, and their total. */
  async paged<INCLUDE extends FIELDS = FIELDS>(
    query: PagedQuery<FIELDS, INCLUDE>,
    options?: RequestOptions,
  ): Promise<PagedResult<Projected<R, INCLUDE, FIELDS>>> {
    return json<PagedResult<Projected<R, INCLUDE, FIELDS>>>(
      await this.post('paged', query, options),
    );
  }

  /** One page of a forward-only cursor over the records that match. */
  async cursor<INCLUDE extends FIELDS = FIELDS>(
    query: CursorQuery<FIELDS, INCLUDE>,
    options?: RequestOptions,
  ): Promise<CursorPage<Projected<R, INCLUDE, FIELDS>>> {
    return json<CursorPage<Projected<R, INCLUDE, FIELDS>>>(
      await this.post('cursor', query, options),
    );
  }

  /** The first record that matches; rejects with 404 `wow.query.not-found` when none does. */
  async single<INCLUDE extends FIELDS = FIELDS>(
    query: FilterSingleQuery<FIELDS, INCLUDE>,
    options?: RequestOptions,
  ): Promise<Projected<R, INCLUDE, FIELDS>> {
    return json<Projected<R, INCLUDE, FIELDS>>(
      await this.post('single', query, options),
    );
  }

  /** How many records match, bounded by the count's limit. */
  async count(
    query: CountQuery<FIELDS>,
    options?: RequestOptions,
  ): Promise<CountResult> {
    return json<CountResult>(await this.post('count', query, options));
  }

  /** The groups and metrics of an aggregation, one row per group. */
  async aggregate<ROW = Record<string, unknown>>(
    query: AggregationQuery<FIELDS, string>,
    options?: RequestOptions,
  ): Promise<ROW[]> {
    return json<ROW[]>(await this.post('aggregate', query, options));
  }

  /**
   * The model's capability descriptor; with the `version` already held, a
   * 304 answers `notModified`.
   */
  async describe(
    version?: string,
    options?: RequestOptions,
  ): Promise<DescriptorResult> {
    const response = await send(this.client, {
      method: 'GET',
      path: this.path,
      headers: {
        [WowHeaders.IF_NONE_MATCH]:
          version === undefined ? undefined : `"${version}"`,
      },
      options,
      notModified: true,
    });
    const tag = entityTag(response);
    if (response.status === 304) {
      return { notModified: true, version: tag ?? version ?? '' };
    }
    return {
      notModified: false,
      ...(tag === undefined ? {} : { version: tag }),
      descriptor: await json<QueryModelDescriptor>(response),
    };
  }

  private post(
    kind: string,
    query: object,
    options?: RequestOptions,
    accept?: string,
    lastEventId?: string,
  ): Promise<Response> {
    return send(this.client, {
      method: 'POST',
      path: this.path,
      headers: { Accept: accept, [WowHeaders.LAST_EVENT_ID]: lastEventId },
      body: { ...query, kind },
      options,
    });
  }
}

/** The query route of a model of an aggregate type. */
export function queryPath(type: AggregateType, model: string): string {
  return `/${type.context}/${type.name}/${model}/query`;
}

/**
 * Queries an aggregate type's snapshots (decision D128), the default read
 * model; only where the aggregate keeps them.
 *
 * @typeParam S - The state's type.
 * @typeParam STATE_FIELDS - The `state.*` paths of the state.
 */
export class SnapshotQueryClient<
  S = unknown,
  STATE_FIELDS extends string = `state.${string}`,
> extends QueryModelClient<
  SnapshotRecord<S>,
  SnapshotSystemField | STATE_FIELDS
> {
  /**
   * @param type - The aggregate type
   * @param client - How the service is reached
   */
  constructor(
    readonly type: AggregateType,
    client?: WowClientOptions,
  ) {
    super(queryPath(type, 'snapshot'), client);
  }
}

/**
 * Queries an aggregate type's event streams (decision D235) on the same
 * engine as its snapshots: one record per commit, `count` counts commits, a
 * condition on an event is an `elementMatch` on `events`, and per-event
 * analytics expand `events` in an aggregation (`elements`). A filter need
 * not name the aggregate, but one that does not reads the log the service
 * appends to: the budget bounds it.
 *
 * @typeParam E - The events' type.
 */
export class EventQueryClient<
  E extends EventView = EventView,
> extends QueryModelClient<EventStreamRecord<E>, EventStreamField> {
  /**
   * @param type - The aggregate type
   * @param client - How the service is reached
   */
  constructor(
    readonly type: AggregateType,
    client?: WowClientOptions,
  ) {
    super(queryPath(type, 'events'), client);
  }
}

/**
 * The datasets of a Wow service (decisions D75, D145): registered at run
 * time, so not in the Manifest. Lists those the caller sees and queries
 * each as a snapshot model is queried.
 */
export class DatasetClient {
  /** @param client - How the service is reached */
  constructor(readonly client: WowClientOptions = {}) {}

  /** The datasets the caller sees, by name. */
  async list(options?: RequestOptions): Promise<DatasetEntry[]> {
    return json<DatasetEntry[]>(
      await send(this.client, { method: 'GET', path: '/wow/dataset', options }),
    );
  }

  /**
   * The query client of the dataset `context.dataset`.
   *
   * @typeParam R - The row's type.
   * @typeParam FIELDS - The dataset's field paths.
   */
  dataset<R = Record<string, unknown>, FIELDS extends string = string>(
    context: string,
    dataset: string,
  ): QueryModelClient<R, FIELDS> {
    return new QueryModelClient<R, FIELDS>(
      `/wow/dataset/${encodeURIComponent(context)}/${encodeURIComponent(dataset)}/query`,
      this.client,
    );
  }
}
