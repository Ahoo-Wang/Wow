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

/*
 * The Wow query route protocol, in one place: a query is POSTed to the
 * model's query route (`/{context}/{aggregate}/{model}/query`) with a `kind`
 * naming the operation; wow-client's `QueryModelClient` speaks it. The
 * `useFetcher…` hooks are the matching `use…Query` hook with one of these
 * executors as `execute`.
 */

import type { Fetcher } from '@ahoo-wang/fetcher';
import { DEFAULT_FETCHER_NAME, getFetcher } from '@ahoo-wang/fetcher';
import { QueryModelClient } from '@ahoo-wang/wow-client';
import type { ListStreamExecutor, QueryExecutor } from '../types.js';

/**
 * Where a `useFetcher…` hook sends its query. Every `useFetcher…Options`
 * extends it, so the two members are declared, and documented, once.
 */
export interface Endpoint {
  /**
   * The query route, resolved against the Fetcher's `baseURL`: for example
   * `/shop/order/snapshot/query` for the snapshots of the `order` aggregate
   * of the `shop` context.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}

/** The operations of a query route that answer JSON. */
export type QueryKind = 'single' | 'list' | 'paged' | 'count';

/**
 * The query route's client. The Fetcher is resolved when the request is
 * sent, so a name that is not registered fails that request, and the hook
 * reports it as its `error`.
 */
function clientOf({ url, fetcher }: Endpoint): QueryModelClient<unknown> {
  return new QueryModelClient(url, { fetcher: getFetcher(fetcher) });
}

/** Runs a query of `kind` on the route and reads the JSON answer. */
export function postQuery<Q extends object, R>(
  endpoint: Endpoint,
  kind: QueryKind,
): QueryExecutor<Q, R> {
  return (query, options) => {
    const client = clientOf(endpoint);
    const run = client[kind] as (query: Q, options: object) => Promise<R>;
    return run.call(client, query, options);
  };
}

/**
 * Opens the stream of a list query on the route: server-sent events, read
 * one record at a time; the stream errors with the problem the server sends
 * midway.
 */
export function postQueryStream<R, Q extends object>(
  endpoint: Endpoint,
): ListStreamExecutor<R, Q> {
  return (query, options) =>
    (clientOf(endpoint) as QueryModelClient<R>).stream(
      query as never,
      options,
    );
}

/**
 * What identifies an endpoint across renders: the url, and the Fetcher by
 * its name, or by its `baseURL` when it has none. A hook runs its query
 * again when this changes; a `new Fetcher({ baseURL })` written inline in
 * render keeps the same identity, so it does not run the query on every
 * render. Two unnamed Fetchers with one `baseURL` but different
 * interceptors count as the same; name them to tell them apart.
 */
export function endpointIdentity({ url, fetcher }: Endpoint): string {
  let by: string;
  if (fetcher === undefined) by = `name:${DEFAULT_FETCHER_NAME}`;
  else if (typeof fetcher === 'string') by = `name:${fetcher}`;
  else if ('name' in fetcher && typeof fetcher.name === 'string')
    by = `name:${fetcher.name}`;
  else by = `baseURL:${fetcher.urlBuilder.baseURL}`;
  return JSON.stringify([url, by]);
}
