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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import type { FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  rootSearch,
  withRootSearch,
  type FilterNode,
  type FilterTree,
} from '../src/index.js';
import { DataWorkbench, type WorkbenchFeatures } from '../src/ui/index.js';
import { ROWS, mine, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

const status: FilterNode = { field: 'status', operator: 'EQ', value: 'PAID' };
const and = (...children: FilterNode[]): FilterTree => ({
  op: 'and',
  children,
});

describe('the root search', () => {
  it('is added at the end, replaced where it is, and taken out when blank', () => {
    const added = withRootSearch(and(status), 'keyword', 'late');
    expect(added.children).toEqual([
      status,
      { field: 'keyword', operator: 'SEARCH', value: 'late' },
    ]);
    expect(rootSearch(added, 'keyword')).toBe('late');

    const replaced = withRootSearch(added, 'keyword', 'lost');
    expect(replaced.children).toHaveLength(2);
    expect(rootSearch(replaced, 'keyword')).toBe('lost');

    expect(withRootSearch(replaced, 'keyword', '  ')).toEqual(and(status));
  });

  it('narrows an advanced tree whose top is an or, rather than joining it', () => {
    const either: FilterTree = { op: 'or', children: [status] };
    expect(withRootSearch(either, 'keyword', 'late')).toEqual(
      and(either, { field: 'keyword', operator: 'SEARCH', value: 'late' }),
    );
  });

  it('leaves a search composed inside a group to the editor', () => {
    const nested = and({
      op: 'or',
      children: [{ field: 'keyword', operator: 'SEARCH', value: 'x' }],
    });
    expect(rootSearch(nested, 'keyword')).toBe('');
  });
});

describe('the search box', () => {
  function open(withSearch = true, features?: WorkbenchFeatures) {
    const base = ordersDefinition();
    const definition = withSearch
      ? ordersDefinition({
          fields: [
            ...base.fields,
            { name: 'keyword', label: 'Search orders', kind: 'search' },
          ],
        })
      : base;
    const paged = vi.fn(() =>
      Promise.resolve({ total: ROWS.length, list: [...ROWS] }),
    );
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource({ paged }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        features={features}
      />,
    );
    const last = () =>
      (
        paged.mock.calls[paged.mock.calls.length - 1] as unknown as [
          FilterPagedQuery,
        ]
      )[0];
    return { paged, last };
  }

  it('searches on Enter, not on every key, by the field’s own label', async () => {
    const { paged, last } = open();
    await screen.findAllByRole('row');
    const box = screen.getByRole('searchbox', { name: 'Search orders' });
    const before = paged.mock.calls.length;

    fireEvent.change(box, { target: { value: 'late' } });
    expect(paged.mock.calls.length).toBe(before);

    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() =>
      expect(JSON.stringify(last().filter)).toContain('late'),
    );
  });

  it('leaves an input method’s Enter to the input method', async () => {
    const { paged } = open();
    await screen.findAllByRole('row');
    const box = screen.getByRole('searchbox', { name: 'Search orders' });
    const before = paged.mock.calls.length;

    fireEvent.change(box, { target: { value: '延迟' } });
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true });
    await act(async () => {});
    expect(paged.mock.calls.length).toBe(before);
  });

  it('takes the search away, and asks again, from its ✕', async () => {
    const { last } = open();
    await screen.findAllByRole('row');
    const box = screen.getByRole('searchbox', { name: 'Search orders' });
    fireEvent.change(box, { target: { value: 'late' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() =>
      expect(JSON.stringify(last().filter)).toContain('late'),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Clear the search' }));
    await waitFor(() =>
      expect(JSON.stringify(last().filter)).not.toContain('late'),
    );
    expect((box as HTMLInputElement).value).toBe('');
  });

  it('is not there when the definition declares nothing to search', async () => {
    open(false);
    await screen.findAllByRole('row');
    expect(screen.queryByRole('searchbox')).toBeNull();
  });

  it('is left out where the host turns it off', async () => {
    open(true, { search: false });
    await screen.findAllByRole('row');
    expect(screen.queryByRole('searchbox')).toBeNull();
  });
});
