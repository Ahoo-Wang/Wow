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

import type {
  AggregationQuery,
  CursorQuery,
  FilterPagedQuery,
} from '@ahoo-wang/fetcher-wow';
import type { RecordData } from '@ahoo-wang/fetcher-view-engine';
import { rowSource } from './rowSource.js';

/**
 * A Wow service as the real-backend scenes' regression twins see it: some
 * recorded documents behind one query resource, answered the way the service
 * answers them.
 */
export interface RecordedWowService {
  /** The origin the scene's fetcher points at; nothing on the network answers it. */
  host: string;
  /**
   * The query resource the documents are, as its path starts — a snapshot
   * resource (`execution_failed/snapshot`) or an event stream
   * (`execution_failed/event`).
   */
  resource: string;
  /** The recorded documents; each install starts from a fresh copy. */
  documents: readonly RecordData[];
  /**
   * Answers a command — a `PUT` — against the documents, changing them the
   * way the service would; `undefined` for a command it does not know.
   * Without it the service takes no commands.
   */
  command?(
    documents: RecordData[],
    path: string,
    body: Record<string, unknown>,
  ): object | undefined;
}

/**
 * Answers what a scene sends to a recorded host — the resource's `paged`,
 * `cursor` and `aggregation` queries, with `rowSource` standing in for the
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
  const query = `/${service.resource}/`;
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    const url = new URL(request.url);
    if (url.origin !== service.host) return original(input, init);
    const body = await request.json();
    let answer: unknown;
    if (request.method === 'PUT')
      answer = service.command?.(documents, url.pathname, body);
    else if (url.pathname.startsWith(query))
      switch (url.pathname.slice(query.length)) {
        case 'paged':
          answer = await source.paged(body as FilterPagedQuery);
          break;
        case 'cursor':
          answer = await source.cursor(body as CursorQuery);
          break;
        case 'aggregation':
          answer = await source.aggregate(body as AggregationQuery);
          break;
      }
    return answer === undefined
      ? Response.json(
          { errorCode: 'NotFound', errorMsg: `Not recorded: ${url.pathname}` },
          { status: 404 },
        )
      : Response.json(answer);
  };
  return () => {
    globalThis.fetch = original;
  };
}
