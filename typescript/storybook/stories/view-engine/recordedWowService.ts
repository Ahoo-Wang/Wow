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

import type { Fetcher } from '@ahoo-wang/fetcher';
import { QueryModelClient } from '@ahoo-wang/wow-client';
import type {
  AggregationQuery,
  CursorQuery,
  FilterPagedQuery,
} from '@ahoo-wang/wow-client/dsl';
import type { RecordData, ViewSource } from '@ahoo-wang/wow-view-engine';
import { viewSourceOf } from '@ahoo-wang/wow-view-store';
import { rowSource } from './rowSource.js';

/**
 * A Wow service as the real-backend scenes' regression twins see it: some
 * recorded documents behind one query route, answered the way the service
 * answers them.
 */
export interface RecordedWowService {
  /** The origin the scene's fetcher points at; nothing on the network answers it. */
  host: string;
  /**
   * The query route the documents are, `/{context}/{aggregate}/{model}/query`:
   * a snapshot route (`/compensation/execution_failed/snapshot/query`) or an
   * event route (`/compensation/execution_failed/events/query`).
   */
  route: string;
  /** The recorded documents; each install starts from a fresh copy. */
  documents: readonly RecordData[];
  /**
   * Answers a command — a `POST` to a route other than the query route —
   * against the documents, changing them the way the service would: its
   * result, a problem (with its `status`) that refuses it, or `undefined`
   * for a command it does not know. Without it the service takes no
   * commands.
   */
  command?(
    documents: RecordData[],
    path: string,
    body: Record<string, unknown>,
  ): object | undefined;
}

/** The query route of a model of an aggregate, as wow-client's `queryPath` writes it. */
export function queryRoute(
  context: string,
  aggregate: string,
  model: 'snapshot' | 'events',
): string {
  return `/${context}/${aggregate}/${model}/query`;
}

/** A problem as a Wow service answers it. */
export function problem(code: string, detail: string, status: number) {
  return { type: `urn:wow:error:${code}`, title: detail, status, code, detail };
}

/**
 * The view source of a query route: its paged, cursor and aggregation
 * queries. A recorded service answers no capability descriptor, so the
 * source does not ask for one.
 */
export function routeSource(fetcher: Fetcher, route: string): ViewSource {
  const { paged, cursor, aggregate } = viewSourceOf(
    new QueryModelClient<object>(route, { fetcher }),
  );
  return { paged, cursor, aggregate };
}

/**
 * Answers what a scene sends to a recorded host — the route's `paged`,
 * `cursor` and `aggregate` queries, with `rowSource` standing in for the
 * service's store, and its commands if it takes any — and lets every other
 * request go where it went before. The scene's own fetcher, clients and
 * engine run unchanged, so what this checks is the scene, not a copy of it.
 *
 * Returns the uninstaller, as `beforeEach` expects.
 */
export function installRecordedWowService(
  service: RecordedWowService,
): () => void {
  const original = globalThis.fetch;
  const documents = structuredClone([...service.documents]);
  const source = rowSource(documents);
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    const url = new URL(request.url);
    if (url.origin !== service.host) return original(input, init);
    const body = await request.json();
    let answer: unknown;
    if (url.pathname === service.route)
      switch (body.kind) {
        case 'paged':
          answer = await source.paged(body as FilterPagedQuery);
          break;
        case 'cursor':
          answer = await source.cursor(body as CursorQuery);
          break;
        case 'aggregate':
          answer = await source.aggregate(body as AggregationQuery);
          break;
      }
    else if (request.method === 'POST')
      answer = service.command?.(documents, url.pathname, body);
    if (answer === undefined)
      answer = problem(
        'wow.route.not-found',
        `Not recorded: ${url.pathname}`,
        404,
      );
    const status = (answer as { type?: unknown; status?: unknown }).type
      ? Number((answer as { status: number }).status)
      : 200;
    return Response.json(answer, {
      status,
      headers:
        status === 200 ? {} : { 'Content-Type': 'application/problem+json' },
    });
  };
  return () => {
    globalThis.fetch = original;
  };
}
