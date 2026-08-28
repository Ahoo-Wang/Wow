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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  useCountQuery,
  useListQuery,
  useListStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '@ahoo-wang/fetcher-react';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  SnapshotQueryClient,
} from '@ahoo-wang/fetcher-wow';
import { useEffect, useMemo, useState } from 'react';
import { expect, userEvent, within } from 'storybook/test';
import { installFetchFixture } from '../fixtures/http';
import type { FixtureViewerUser } from '../fixtures/viewer';

type Scenario = 'single' | 'list' | 'paged' | 'count' | 'stream';

function WowQueryDemo({ scenario }: { scenario: Scenario }) {
  const activeFilter = filter.eq('active', true);
  const fetcher = useMemo(
    () => new Fetcher({ baseURL: 'https://api.example.test' }),
    [],
  );
  const client = useMemo(
    () =>
      new SnapshotQueryClient<FixtureViewerUser>({
        fetcher,
        basePath: '/users',
      }),
    [fetcher],
  );
  const streamClient = useMemo(
    () =>
      new SnapshotQueryClient<FixtureViewerUser[]>({
        fetcher,
        basePath: '/users',
      }),
    [fetcher],
  );
  const single = useSingleQuery<FixtureViewerUser>({
    initialQuery: singleQuery({ filter: activeFilter }),
    autoExecute: false,
    execute: query => client.singleState(query),
  });
  const list = useListQuery<FixtureViewerUser>({
    initialQuery: listQuery({ filter: activeFilter, limit: 20 }),
    autoExecute: false,
    execute: query => client.listState(query),
  });
  const paged = usePagedQuery<FixtureViewerUser>({
    initialQuery: pagedQuery({
      filter: activeFilter,
      pagination: { index: 1, size: 10 },
    }),
    autoExecute: false,
    execute: query => client.pagedState(query),
  });
  const count = useCountQuery({
    initialQuery: activeFilter,
    autoExecute: false,
    execute: query => client.count(query),
  });
  const stream = useListStreamQuery<FixtureViewerUser[]>({
    initialQuery: listQuery({ filter: activeFilter, limit: 0 }),
    autoExecute: false,
    execute: query => streamClient.listStateStream(query),
  });
  const [streamOutput, setStreamOutput] = useState('idle');

  useEffect(() => {
    if (!stream.result) return;
    void (async () => {
      const reader = stream.result.getReader();
      const first = await reader.read();
      setStreamOutput(
        `Stream · ${first.value?.data.map(user => user.name).join(', ')}`,
      );
    })();
  }, [stream.result]);

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
  if (scenario === 'stream') output = streamOutput;

  return (
    <section className="story-stack" aria-label="Wow query hook">
      <button onClick={run}>Run query</button>
      <output className="story-output" aria-live="polite">
        {output}
      </output>
    </section>
  );
}

const meta = {
  title: 'React Hooks/Wow Queries',
  component: WowQueryDemo,
  beforeEach: installFetchFixture,
  args: { scenario: 'single' },
  argTypes: { scenario: { control: 'radio' } },
} satisfies Meta<typeof WowQueryDemo>;

export default meta;
type Story = StoryObj<typeof meta>;

async function queryAndExpect(canvasElement: HTMLElement, text: string) {
  const canvas = within(canvasElement);
  await userEvent.click(canvas.getByRole('button', { name: 'Run query' }));
  await expect(await canvas.findByText(text)).toBeVisible();
}

export const Single: Story = {
  args: { scenario: 'single' },
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Single · Ada'),
};

export const List: Story = {
  args: { scenario: 'list' },
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'List · Ada, Lin'),
};

export const Paged: Story = {
  args: { scenario: 'paged' },
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Paged · 2 of 2'),
};

export const Count: Story = {
  args: { scenario: 'count' },
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Count · 2'),
};

export const Streaming: Story = {
  args: { scenario: 'stream' },
  play: ({ canvasElement }) =>
    queryAndExpect(canvasElement, 'Stream · Ada, Lin'),
};
