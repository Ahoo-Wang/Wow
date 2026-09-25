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
 * A hand-over kept in a host's history entry (the compensation console keeps
 * it in `history.state`): the workbench names the view in the address and
 * the hand-over rides along with the entry. Going back to that entry from
 * another view names the handed view and hands it over again in one move —
 * the view opens under what was handed, not bare.
 */

import { useState } from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewHandOver,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

const views: ViewInstance[] = [
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
  {
    id: 'other',
    definitionId: 'orders',
    title: 'Other list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

/** What a board handed over: the page's region, and the reader's state. */
const HANDED: ViewHandOver = {
  kind: 'view',
  definitionId: 'orders',
  instanceId: 'list',
  scopeFilter: {
    op: 'and',
    children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
  },
  filter: {
    op: 'and',
    children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
  },
};

/** One history entry: the view the address names, and the entry's state. */
interface Entry {
  instanceId: string;
  handOver: ViewHandOver | null;
}

function setup({ clone = true }: { clone?: boolean } = {}) {
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: views }),
    resolveSource: () => testSource(),
  });
  // A host whose address is a history: a switch in the workbench pushes an
  // entry with no state, as a router's `setSearchParams` does.
  const history: Entry[] = [{ instanceId: 'list', handOver: HANDED }];
  let go: (entry: Entry) => void = () => undefined;
  function Host() {
    const [entry, setEntry] = useState<Entry>(history[0]!);
    go = setEntry;
    return (
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={entry.instanceId}
        handOver={entry.handOver}
        onInstanceChange={id => {
          if (id === null || id === entry.instanceId) return;
          const next = { instanceId: id, handOver: null };
          history.push(next);
          setEntry(next);
        }}
      />
    );
  }
  render(<Host />);
  return {
    engine,
    /** The browser's Back: the entry before, its state read afresh. */
    back() {
      history.pop();
      const entry = history[history.length - 1]!;
      act(() =>
        go(
          clone
            ? { ...entry, handOver: structuredClone(entry.handOver) }
            : entry,
        ),
      );
    },
    switchTo(instanceId: string) {
      const next = { instanceId, handOver: null };
      history.push(next);
      act(() => go(next));
    },
  };
}

/** What 「正在显示」 says: the scope's badges, and the whole bar. */
function applied(): { scoped: string[]; text: string } {
  const bar = document.querySelector<HTMLElement>('[data-slot="applied-bar"]');
  return {
    scoped: [...(bar?.querySelectorAll('[data-scoped]') ?? [])].map(
      item => item.textContent ?? '',
    ),
    text: bar?.textContent ?? '',
  };
}

describe('going back to a view handed over (the host’s history)', () => {
  it.each([
    ['read afresh', true],
    ['the very object it handed before', false],
  ])('opens it again under what was handed, not bare: %s', async (_, clone) => {
    const { switchTo, back } = setup({ clone });
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    await waitFor(() =>
      expect(applied().scoped).toEqual([expect.stringMatching(/CN/)]),
    );
    expect(applied().text).toMatch(/PENDING/);

    switchTo('other');
    await screen.findByRole('heading', { level: 2, name: 'Other list' });
    await waitFor(() => expect(applied().scoped).toEqual([]));

    back();
    await screen.findByRole('heading', { level: 2, name: 'Order list' });
    await waitFor(() =>
      expect(applied().scoped).toEqual([expect.stringMatching(/CN/)]),
    );
    expect(applied().text).toMatch(/PENDING/);
  });
});
