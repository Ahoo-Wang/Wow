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
import { AntdProvider } from '../shared/AntdProvider.js';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  useCountQuery,
  useListQuery,
  useListStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '@ahoo-wang/wow-react';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import { useMemo } from 'react';
import { installFetchFixture } from '../fixtures/http';
import type { FixtureUser } from '../fixtures/users';

type Scenario = 'single' | 'list' | 'paged' | 'count' | 'stream';

function WowQueryDemo({ scenario }: { scenario: Scenario }) {
  const activeFilter = filter.eq('active', true);
  const fetcher = useMemo(
    () => new Fetcher({ baseURL: 'https://api.example.test' }),
    [],
  );
  const client = useMemo(
    () =>
      new SnapshotQueryClient<FixtureUser>({
        fetcher,
        basePath: '/users',
      }),
    [fetcher],
  );
  // Each hook takes the client method as `execute` and hands the abort
  // controller on, so a newer query or an unmount cancels the request.
  const single = useSingleQuery<FixtureUser>({
    initialQuery: singleQuery({ filter: activeFilter }),
    autoExecute: false,
    execute: (query, attributes, abortController) =>
      client.singleState(query, attributes, abortController),
  });
  const list = useListQuery<FixtureUser>({
    initialQuery: listQuery({ filter: activeFilter, limit: 20 }),
    autoExecute: false,
    execute: (query, attributes, abortController) =>
      client.listState(query, attributes, abortController),
  });
  const paged = usePagedQuery<FixtureUser>({
    initialQuery: pagedQuery({
      filter: activeFilter,
      pagination: { index: 1, size: 10 },
    }),
    autoExecute: false,
    execute: (query, attributes, abortController) =>
      client.pagedState(query, attributes, abortController),
  });
  const count = useCountQuery({
    initialQuery: activeFilter,
    autoExecute: false,
    execute: (query, attributes, abortController) =>
      client.count(query, attributes, abortController),
  });
  // The hook reads the stream itself and keeps the rows as `items`.
  const stream = useListStreamQuery<FixtureUser>({
    initialQuery: listQuery({ filter: activeFilter, limit: 20 }),
    autoExecute: false,
    execute: (query, attributes, abortController) =>
      client.listStateStream(query, attributes, abortController),
  });

  const run = () => {
    if (scenario === 'single') void single.execute();
    if (scenario === 'list') void list.execute();
    if (scenario === 'paged') void paged.execute();
    if (scenario === 'count') void count.execute();
    if (scenario === 'stream') void stream.execute();
  };

  let output = 'idle';
  if (scenario === 'single' && single.result) {
    output = `Single · ${single.result.name}`;
  }
  if (scenario === 'list' && list.result) {
    output = `List · ${list.result.map(user => user.name).join(', ')}`;
  }
  if (scenario === 'paged' && paged.result) {
    output = `Paged · ${paged.result.list.length} of ${paged.result.total}`;
  }
  if (scenario === 'count' && count.result !== undefined) {
    output = `Count · ${count.result}`;
  }
  if (scenario === 'stream' && stream.loading) {
    output = `Streaming · ${stream.items.length} received`;
  }
  if (scenario === 'stream' && stream.done) {
    output = `Stream · ${stream.items.map(user => user.name).join(', ')}`;
  }

  return (
    <section className="story-stack" aria-label="Wow query hook">
      <button onClick={run}>Run query</button>
      <output className="story-output" aria-live="polite">
        {output}
      </output>
    </section>
  );
}

const scene = {
  domain: 'CQRS query state',
  summary: 'Exercise typed Wow query shapes through their React hook adapters.',
  fixture: 'Local Wow responses · typed filters',
  setup: 'A query hook starts with deterministic aggregate data and filters.',
  observe: 'Single, list, page, count, and stream state stay visible.',
};

const meta = {
  parameters: { docs: { story: { inline: false, height: '480px' } } },
  decorators: [
    (Story, context) => (
      <AntdProvider>
        <ScenarioFrame title={context.name} {...scene}>
          <Story />
        </ScenarioFrame>
      </AntdProvider>
    ),
  ],
  title: 'React Hooks/Wow Queries',
  component: WowQueryDemo,
  beforeEach: installFetchFixture,
  args: { scenario: 'single' },
  argTypes: { scenario: { table: { disable: true } } },
} satisfies Meta<typeof WowQueryDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Single: Story = {
  args: { scenario: 'single' },
};

export const List: Story = {
  args: { scenario: 'list' },
};

export const Paged: Story = {
  args: { scenario: 'paged' },
};

export const Count: Story = {
  args: { scenario: 'count' },
};

export const Streaming: Story = {
  args: { scenario: 'stream' },
};
