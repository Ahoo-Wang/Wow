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
 * Characterization of what makes a query hook run again: the controlled
 * `query` compared by content, `attributes`, `url`, `fetcher` and
 * `autoExecute`. It pins the behaviour of today, fetcher-react 5.1.3
 * underneath, for the refactor of `docs/design/refactor-2026-09.md`.
 *
 * Cells that batch B3 changes on purpose (F7: `url` and `fetcher` are part of
 * the request's identity) assert today's value and carry a
 * `B3 changes this` comment naming the new one.
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  SnapshotQueryClient,
  type FilterListQuery,
} from '@ahoo-wang/wow-client';
import {
  useFetcherListQuery,
  useFetcherListStreamQuery,
  useListQuery,
  useListStreamQuery,
} from '../src';
import {
  BASE_URL,
  fakeServer,
  json,
  sse,
  type FakeServer,
} from './support/fakeServer';

interface Props {
  query: FilterListQuery;
  attributes?: Record<string, unknown>;
  url?: string;
  fetcher?: Fetcher;
  autoExecute?: boolean;
}

interface Hook {
  status: string;
  execute(): Promise<void>;
  getQuery(): FilterListQuery | undefined;
  setQuery(query: FilterListQuery): void;
}

const LIST_URL = 'order/snapshot/list/state';
const OTHER_URL = 'cart/snapshot/list/state';

const paidQuery = listQuery({
  filter: filter.eq('state.status', 'PAID'),
  limit: 10,
});
const shippedQuery = listQuery({
  filter: filter.eq('state.status', 'SHIPPED'),
  limit: 10,
});

/** Answers a list query with no rows, as JSON or as an event stream. */
function listServer(): FakeServer {
  return fakeServer(request =>
    request.headers.get('Accept')?.includes('text/event-stream')
      ? sse()
      : json([]),
  );
}

/** Records the `tenant` attribute of every exchange the Fetcher sends. */
function readTenants(fetcher: Fetcher): unknown[] {
  const tenants: unknown[] = [];
  fetcher.interceptors.request.use({
    name: 'ReadTenant',
    order: 0,
    intercept(exchange) {
      tenants.push(exchange.attributes.get('tenant'));
    },
  });
  return tenants;
}

interface Family {
  name: string;
  /** Whether the hook takes `url` and `fetcher` options. */
  url: boolean;
  render(
    server: FakeServer,
    initialProps: Props,
  ): {
    result: { current: Hook };
    rerender(props: Props): void;
  };
}

const families: Family[] = [
  {
    name: 'useListQuery (query client as execute)',
    url: false,
    render(server, initialProps) {
      const client = new SnapshotQueryClient<unknown>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(
        ({ query, attributes, autoExecute }: Props) =>
          useListQuery<unknown>({
            query,
            attributes,
            autoExecute,
            execute: (q, a, abortController) =>
              client.listState(q, a, abortController),
          }),
        { initialProps },
      );
    },
  },
  {
    name: 'useFetcherListQuery (url)',
    url: true,
    render(server, initialProps) {
      return renderHook(
        ({ query, attributes, autoExecute, url, fetcher }: Props) =>
          useFetcherListQuery<unknown>({
            query,
            attributes,
            autoExecute,
            url: url ?? LIST_URL,
            fetcher: fetcher ?? server.fetcher,
          }),
        { initialProps },
      );
    },
  },
  {
    name: 'useListStreamQuery (query client as execute)',
    url: false,
    render(server, initialProps) {
      const client = new SnapshotQueryClient<unknown>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(
        ({ query, attributes, autoExecute }: Props) =>
          useListStreamQuery<unknown>({
            query,
            attributes,
            autoExecute,
            execute: (q, a, abortController) =>
              client.listStateStream(q, a, abortController),
          }),
        { initialProps },
      );
    },
  },
  {
    name: 'useFetcherListStreamQuery (url)',
    url: true,
    render(server, initialProps) {
      return renderHook(
        ({ query, attributes, autoExecute, url, fetcher }: Props) =>
          useFetcherListStreamQuery<unknown>({
            query,
            attributes,
            autoExecute,
            url: url ?? LIST_URL,
            fetcher: fetcher ?? server.fetcher,
          }),
        { initialProps },
      );
    },
  },
];

/** Lets any request the last render started go out and settle. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

/** A value as the server receives it: JSON drops the `undefined` fields. */
function wire(value: unknown): unknown {
  return JSON.parse(JSON.stringify(value));
}

describe.each(families)('what runs $name again', family => {
  async function mounted(props: Partial<Props> = {}) {
    const server = listServer();
    const hook = family.render(server, { query: paidQuery, ...props });
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    expect(server.requests).toHaveLength(1);
    return { server, ...hook };
  }

  describe('the controlled query, compared by content', () => {
    it('a new object with the same content does not run it again', async () => {
      const { server, rerender } = await mounted();
      rerender({ query: structuredClone(paidQuery) });
      await settle();
      expect(server.requests).toHaveLength(1);
    });

    it('the same content with its keys in another order does not run it again', async () => {
      const { server, rerender } = await mounted();
      const { filter: f, ...rest } = structuredClone(paidQuery);
      rerender({ query: { ...rest, filter: f } });
      await settle();
      expect(server.requests).toHaveLength(1);
    });

    it('other content runs it again with the new query', async () => {
      const { server, rerender, result } = await mounted();
      rerender({ query: shippedQuery });
      await waitFor(() => expect(server.requests).toHaveLength(2));
      expect(server.requests[1].body).toEqual(wire(shippedQuery));
      expect(result.current.getQuery()).toEqual(shippedQuery);
    });

    it('setQuery() overrides it until the option changes content', async () => {
      const { server, rerender, result } = await mounted();
      act(() => result.current.setQuery(shippedQuery));
      await waitFor(() => expect(server.requests).toHaveLength(2));
      rerender({ query: structuredClone(paidQuery) });
      await settle();
      expect(server.requests).toHaveLength(2);
      expect(result.current.getQuery()).toEqual(shippedQuery);
      const third = listQuery({ filter: filter.id('o3') });
      rerender({ query: third });
      await waitFor(() => expect(server.requests).toHaveLength(3));
      expect(result.current.getQuery()).toEqual(third);
    });
  });

  describe('attributes', () => {
    it('a change does not run it again; the next execute() sends the new ones', async () => {
      const server = listServer();
      const tenants = readTenants(server.fetcher);
      const hook = family.render(server, {
        query: paidQuery,
        attributes: { tenant: 't1' },
      });
      await waitFor(() => expect(hook.result.current.status).toBe('success'));
      hook.rerender({ query: paidQuery, attributes: { tenant: 't2' } });
      await settle();
      expect(server.requests).toHaveLength(1);
      await act(() => hook.result.current.execute());
      expect(tenants).toEqual(['t1', 't2']);
    });
  });

  describe('autoExecute', () => {
    it('turning it on runs the query; turning it off again does not', async () => {
      const server = listServer();
      const hook = family.render(server, {
        query: paidQuery,
        autoExecute: false,
      });
      await settle();
      expect(server.requests).toHaveLength(0);
      hook.rerender({ query: paidQuery, autoExecute: true });
      await waitFor(() => expect(server.requests).toHaveLength(1));
      hook.rerender({ query: paidQuery, autoExecute: false });
      await settle();
      expect(server.requests).toHaveLength(1);
    });
  });
});

describe.each(families.filter(family => family.url))(
  'what runs $name again: url',
  family => {
    it('a url change does not run it again; the next execute() posts to the new url', async () => {
      const server = listServer();
      const { rerender, result } = family.render(server, { query: paidQuery });
      await waitFor(() => expect(result.current.status).toBe('success'));
      rerender({ query: paidQuery, url: OTHER_URL });
      await settle();
      // B3 changes this (F7): the url is part of the request, so 2 requests.
      expect(server.requests).toHaveLength(1);
      await act(() => result.current.execute());
      expect(server.requests[1].url).toBe(`${BASE_URL}${OTHER_URL}`);
    });
  },
);

describe('a fetcher change', () => {
  function otherFetcher() {
    return new Fetcher({ baseURL: 'https://other.example.test/' });
  }

  // fetcher-react's useFetcher resolves the Fetcher during render and keys
  // its execute callback on the instance, which reaches the query state's
  // effect. F7 says a fetcher change does not run it again: for the request
  // hooks it already does. The same path makes a `new Fetcher()` written
  // inline in render request without end; B3 must key on a stable identity.
  it('runs useFetcherListQuery again through the new Fetcher', async () => {
    const server = listServer();
    const hook = families[1].render(server, { query: paidQuery });
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    hook.rerender({ query: paidQuery, fetcher: otherFetcher() });
    // B2 changes this: both URL paths read the Fetcher when the request is
    // sent, so a change does not run the hook again, as for the stream hook
    // below; B3 then makes the fetcher part of the request for both.
    await waitFor(() => expect(server.requests).toHaveLength(2));
    expect(server.requests[1].url).toBe(
      `https://other.example.test/${LIST_URL}`,
    );
  });

  it('does not run useFetcherListStreamQuery again; the next execute() uses the new Fetcher', async () => {
    const server = listServer();
    const hook = families[3].render(server, { query: paidQuery });
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    hook.rerender({ query: paidQuery, fetcher: otherFetcher() });
    await settle();
    // B3 changes this (F7): the fetcher is part of the request, so it runs
    // again, as useFetcherListQuery already does.
    expect(server.requests).toHaveLength(1);
    await act(() => hook.result.current.execute());
    expect(server.requests[1].url).toBe(
      `https://other.example.test/${LIST_URL}`,
    );
  });
});
