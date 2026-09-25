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
 * A page that mounts every hook of the package once, each with a query and
 * `autoExecute` on, and prints what each shows. The SSR and hydration suites
 * render it on the server and hydrate it on the client.
 */

import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import {
  useCountQuery,
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherListStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
  useListQuery,
  useListStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '../../src';

const paid = filter.eq('state.status', 'PAID');

/** The hook names, in the order the page prints them. */
export const HOOKS = [
  'useSingleQuery',
  'useListQuery',
  'usePagedQuery',
  'useCountQuery',
  'useListStreamQuery',
  'useFetcherSingleQuery',
  'useFetcherListQuery',
  'useFetcherPagedQuery',
  'useFetcherCountQuery',
  'useFetcherListStreamQuery',
] as const;

interface Shown {
  status: string;
  loading: boolean;
}

function Line({ name, hook }: { name: string; hook: Shown }) {
  return (
    <li data-hook={name}>
      {`${name}:${hook.status}:${hook.loading ? 'loading' : 'still'}`}
    </li>
  );
}

export function SsrProbe({
  fetcher,
  autoExecute,
}: {
  fetcher: Fetcher;
  autoExecute?: boolean;
}) {
  const client = new SnapshotQueryClient<unknown>({
    fetcher,
    basePath: 'order',
  });
  const url = (path: string) => ({ fetcher, url: `order/snapshot/${path}` });
  const hooks: Shown[] = [
    useSingleQuery<unknown>({
      initialQuery: singleQuery({ filter: paid }),
      autoExecute,
      execute: (q, a, c) => client.singleState(q, a, c),
    }),
    useListQuery<unknown>({
      initialQuery: listQuery({ filter: paid }),
      autoExecute,
      execute: (q, a, c) => client.listState(q, a, c),
    }),
    usePagedQuery<unknown>({
      initialQuery: pagedQuery({ filter: paid }),
      autoExecute,
      execute: (q, a, c) => client.pagedState(q, a, c),
    }),
    useCountQuery({
      initialQuery: paid,
      autoExecute,
      execute: (q, a, c) => client.count(q, a, c),
    }),
    useListStreamQuery<unknown>({
      initialQuery: listQuery({ filter: paid }),
      autoExecute,
      execute: (q, a, c) => client.listStateStream(q, a, c),
    }),
    useFetcherSingleQuery<unknown>({
      ...url('single/state'),
      initialQuery: singleQuery({ filter: paid }),
      autoExecute,
    }),
    useFetcherListQuery<unknown>({
      ...url('list/state'),
      initialQuery: listQuery({ filter: paid }),
      autoExecute,
    }),
    useFetcherPagedQuery<unknown>({
      ...url('paged/state'),
      initialQuery: pagedQuery({ filter: paid }),
      autoExecute,
    }),
    useFetcherCountQuery({
      ...url('count'),
      initialQuery: paid,
      autoExecute,
    }),
    useFetcherListStreamQuery<unknown>({
      ...url('list/state'),
      initialQuery: listQuery({ filter: paid }),
      autoExecute,
    }),
  ];
  return (
    <ul>
      {hooks.map((hook, i) => (
        <Line key={HOOKS[i]} name={HOOKS[i]} hook={hook} />
      ))}
    </ul>
  );
}
