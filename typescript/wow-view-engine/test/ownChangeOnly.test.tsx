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
 * A press applies its own change and nothing else (audit F2, the owner's
 * review of 2026-11-12). Enter or ✕ in the search box, and the ✕ on a
 * condition in force, used to apply the whole draft: a search also ran a
 * sort or a condition the reader had left undecided in the editor. Now each
 * puts its own conditions in force and leaves what waits waiting.
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterOperator, SortDirection } from '@ahoo-wang/wow-client/dsl';
import {
  MemoryViewStore,
  ViewEngine,
  type FilterTree,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { rootSearch } from '../src/filter/search.js';
import { useFilterEditor, useSearchBox } from '../src/react/index.js';
import {
  nextTask,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const PAID = { field: 'status', operator: FilterOperator.EQ, value: 'PAID' };
const SAVED_FILTER: FilterTree = { op: 'and', children: [PAID] };
const BY_AMOUNT = [{ field: 'amount', direction: SortDirection.DESC }];

const SAVED: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig({ filter: SAVED_FILTER }),
};

async function opened() {
  const base = ordersDefinition();
  const definition = ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'keyword', label: 'Search orders', kind: 'search' },
    ],
  });
  const source = testSource();
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => source),
    store: new MemoryViewStore({ instances: [SAVED] }),
    environment: testEnvironment().environment,
  });
  const runtime = (await engine.open('mine')) as RecordViewRuntime;
  await nextTask();
  return { runtime, source };
}

/** How many times the source was asked for a page, by either way of paging. */
const asked = (source: ViewSource) =>
  vi.mocked(source.paged).mock.calls.length +
  vi.mocked(source.cursor).mock.calls.length;

describe('applying conditions alone', () => {
  it('puts them in force and leaves what waits in the draft waiting', async () => {
    const { runtime, source } = await opened();
    const before = asked(source);
    const sortInForce = runtime.getSnapshot().applied.sort;

    runtime.edit({ sort: BY_AMOUNT });
    const none: FilterTree = { op: 'and', children: [] };
    runtime.apply({ filter: none });

    const state = runtime.getSnapshot();
    expect(state.applied.filter).toEqual(none);
    // The sort is still the one the rows came back under, and still waits.
    expect(state.applied.sort).toEqual(sortInForce);
    expect(state.draft.sort).toEqual(BY_AMOUNT);
    expect(asked(source)).toBe(before + 1);
  });

  it('is refused like any apply when what it would put in force is invalid', async () => {
    const { runtime, source } = await opened();
    const before = asked(source);
    const inForce = runtime.getSnapshot().applied.filter;

    runtime.apply({
      filter: {
        op: 'and',
        children: [
          { field: 'no-such-field', operator: FilterOperator.EQ, value: 1 },
        ],
      },
    });

    expect(runtime.getSnapshot().applied.filter).toEqual(inForce);
    expect(asked(source)).toBe(before);
  });
});

describe('the search box', () => {
  it('applies the search on Enter and leaves a waiting sort waiting', async () => {
    const { runtime, source } = await opened();
    const { result } = renderHook(() => useSearchBox(runtime));
    const before = asked(source);
    const sortInForce = runtime.getSnapshot().applied.sort;

    act(() => runtime.edit({ sort: BY_AMOUNT }));
    act(() => result.current!.set('late'));
    act(() => result.current!.submit());

    const state = runtime.getSnapshot();
    expect(rootSearch(state.applied.filter, 'keyword')).toBe('late');
    // The view's own condition stays in force beside it.
    expect(state.applied.filter.children).toContainEqual(PAID);
    expect(state.applied.sort).toEqual(sortInForce);
    expect(state.draft.sort).toEqual(BY_AMOUNT);
    expect(asked(source)).toBe(before + 1);
  });

  it('takes the search out with its ✕, and nothing else with it', async () => {
    const { runtime } = await opened();
    const { result } = renderHook(() => useSearchBox(runtime));
    act(() => result.current!.set('late'));
    act(() => result.current!.submit());
    await waitFor(() => expect(result.current!.applied).toBe('late'));
    const sortInForce = runtime.getSnapshot().applied.sort;

    act(() => runtime.edit({ sort: BY_AMOUNT }));
    act(() => result.current!.clear());

    const state = runtime.getSnapshot();
    expect(rootSearch(state.applied.filter, 'keyword')).toBe('');
    expect(rootSearch(state.draft.filter, 'keyword')).toBe('');
    expect(state.applied.sort).toEqual(sortInForce);
    expect(state.draft.sort).toEqual(BY_AMOUNT);
  });
});

describe('the ✕ on a condition in force', () => {
  it('takes that condition out of force and out of the draft, and leaves the rest waiting', async () => {
    const { runtime, source } = await opened();
    const { result } = renderHook(() => useFilterEditor(runtime));
    const before = asked(source);
    const sortInForce = runtime.getSnapshot().applied.sort;

    act(() => runtime.edit({ sort: BY_AMOUNT }));
    act(() => result.current.unset([[0]]));

    const state = runtime.getSnapshot();
    // Set back to "nothing said": the row stays in the editor, its value gone.
    for (const tree of [state.applied.filter, state.draft.filter]) {
      expect(tree.children).toHaveLength(1);
      expect(tree.children[0]).toMatchObject({ field: 'status' });
      expect(tree.children[0]).not.toEqual(PAID);
    }
    expect(state.applied.sort).toEqual(sortInForce);
    expect(state.draft.sort).toEqual(BY_AMOUNT);
    expect(asked(source)).toBe(before + 1);
  });
});
