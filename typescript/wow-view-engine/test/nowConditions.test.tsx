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

import { filter as wow } from '@ahoo-wang/wow-client';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type FilterTree,
  type ViewSource,
} from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import type { ViewRuntime } from '../src/index.js';
import { AppliedBar } from '../src/ui/AppliedBar.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * Pending, and not yet overdue: `NOR BEFORE_NOW` rather than `AFTER_NOW`,
 * which is strict and would miss the deadline's own millisecond. The shape
 * of the compensation console's "executing" queue (its rebuild, batch 2).
 */
const NOT_OVERDUE: FilterTree = {
  op: 'and',
  children: [
    { field: 'status', operator: 'EQ', value: 'PENDING' },
    {
      op: 'nor',
      children: [{ field: 'dueAt', operator: 'BEFORE_NOW', value: null }],
    },
  ],
};

const EXPECTED = wow.and([
  wow.eq('status', 'PENDING'),
  wow.nor([wow.beforeNow('dueAt')]),
]);

function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'dueAt', label: '到期时间', kind: 'datetime', sortable: true },
    ],
    views: [
      {
        id: 'not-overdue',
        title: 'Not overdue',
        config: recordConfig({ filter: NOT_OVERDUE, filterMode: 'advanced' }),
      },
    ],
  });
}

function engineOver(store: MemoryViewStore, source: ViewSource) {
  return new ViewEngine({
    definitions: [definition()],
    store,
    resolveSource: () => source,
  });
}

/** The filter of the last page the source was asked for. */
function lastFilter(source: ViewSource) {
  const calls = vi.mocked(source.paged).mock.calls;
  return calls[calls.length - 1]?.[0].filter;
}

function Bar({ runtime }: { runtime: ViewRuntime }) {
  const filter = useFilterEditor(runtime);
  return <AppliedBar filter={filter} asked />;
}

describe('a condition against the service clock', () => {
  it('is admitted in a system view and compiled to BEFORE_NOW', async () => {
    const source = testSource();
    const engine = engineOver(new MemoryViewStore({ instances: [] }), source);
    expect(engine.definitionIssues('orders')).toEqual([]);

    const runtime = await engine.open('system:orders:not-overdue');
    runtime.apply();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());

    // No moment of the engine's own: the service reads its clock.
    expect(lastFilter(source)).toEqual(EXPECTED);
    expect(runtime.getSnapshot().issues).toEqual([]);
    engine.dispose();
  });

  it('reads in the applied bar as the operator alone', async () => {
    const source = testSource();
    const engine = engineOver(new MemoryViewStore({ instances: [] }), source);
    const runtime = await engine.open('system:orders:not-overdue');
    runtime.apply();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());

    render(
      <MessagesProvider messages={zhCN}>
        <Bar runtime={runtime} />
      </MessagesProvider>,
    );

    // The operator is the whole condition, under the group that negates it.
    await waitFor(() =>
      expect(screen.getByText('排除 到期时间 早于现在')).toBeDefined(),
    );
    engine.dispose();
  });

  it('survives a save as a personal view and a reopen', async () => {
    const store = new MemoryViewStore({ instances: [] });
    const first = engineOver(store, testSource());
    const runtime = await first.open('system:orders:not-overdue');
    const saved = await first.saveAs(runtime, {
      title: 'Mine',
      scope: 'personal',
    });
    first.dispose();

    // A second engine over the same store: what a reload opens.
    const source = testSource();
    const second = engineOver(store, source);
    const reopened = await second.open(saved.id);
    expect(reopened.getSnapshot().issues).toEqual([]);
    const { applied } = reopened.getSnapshot();
    expect(applied.kind === 'record' && applied.filter).toEqual(NOT_OVERDUE);
    reopened.apply();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    expect(lastFilter(source)).toEqual(EXPECTED);
    second.dispose();
  });
});
