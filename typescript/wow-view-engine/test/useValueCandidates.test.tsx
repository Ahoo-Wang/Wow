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

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type FilterTree,
  type ValueCandidateSource,
  type ValueCandidates,
} from '../src/index.js';
import {
  useFilterEditor,
  useOpenView,
  useValueCandidates,
} from '../src/react/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { mine, settle } from './fixtures/ui.js';

afterEach(cleanup);

interface Call {
  query: string;
  signal?: AbortSignal;
}

/** A source answering from `answers` by query, recording every call. */
function fakeSource(
  answers: Record<string, ValueCandidates | Error>,
  field = 'warehouse',
): { source: ValueCandidateSource; calls: Call[] } {
  const calls: Call[] = [];
  return {
    calls,
    source: {
      field,
      search(query, signal) {
        calls.push({ query, signal });
        const answer = answers[query] ?? { values: [], complete: true };
        return answer instanceof Error
          ? Promise.reject(answer)
          : Promise.resolve(answer);
      },
    },
  };
}

const WHOLE: ValueCandidates = {
  values: [
    { value: 'CN', count: 2 },
    { value: 'JP', count: 1 },
  ],
  complete: true,
};

describe('useValueCandidates', () => {
  it('asks nothing until the list opens, then the whole list at once and a fragment once typing pauses', async () => {
    const { source, calls } = fakeSource({
      '': WHOLE,
      j: { values: [{ value: 'JP', count: 1 }], complete: true },
    });
    const { result, rerender } = renderHook(
      ({ query, active }) => useValueCandidates(source, query, active),
      { initialProps: { query: '', active: false } },
    );
    // The whole list is asked at no delay, so it would be asked by now.
    await settle();
    expect(calls).toEqual([]);
    expect(result.current.status).toBe('idle');

    rerender({ query: '', active: true });
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.values).toEqual(WHOLE.values);
    expect(result.current.complete).toBe(true);
    expect(result.current.query).toBe('');

    rerender({ query: ' j', active: true });
    // Still answering the text before, which a control narrows meanwhile.
    expect(result.current.query).toBe('');
    rerender({ query: ' j ', active: true });
    await waitFor(() => expect(result.current.query).toBe('j'));
    expect(result.current.values).toEqual([{ value: 'JP', count: 1 }]);
    // One ask for the pause, not one per keystroke.
    expect(calls.map(call => call.query)).toEqual(['', 'j']);
  });

  it('aborts what is on its way when the list closes', async () => {
    let resolve: (answer: ValueCandidates) => void = () => {};
    const calls: Call[] = [];
    const source: ValueCandidateSource = {
      field: 'warehouse',
      search(query, signal) {
        calls.push({ query, signal });
        return new Promise(done => {
          resolve = done;
        });
      },
    };
    const { result, rerender } = renderHook(
      ({ active }) => useValueCandidates(source, '', active),
      { initialProps: { active: true } },
    );
    await waitFor(() => expect(result.current.status).toBe('loading'));
    rerender({ active: false });
    expect(calls[0].signal?.aborted).toBe(true);
    // An answer that arrives after all is dropped.
    await act(async () => resolve(WHOLE));
    expect(result.current.values).toEqual([]);
  });

  it("says why the source failed in the source's own words, and asks again on retry", async () => {
    const { source, calls } = fakeSource({
      '': new Error('Service unavailable'),
    });
    const { result } = renderHook(() => useValueCandidates(source, '', true));
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.reason).toBe('Service unavailable');

    act(() => result.current.retry());
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it("does not show one field's values under another", async () => {
    const first = fakeSource({ '': WHOLE });
    const second = fakeSource({ '': new Error('x') }, 'status');
    const { result, rerender } = renderHook(
      ({ source }) => useValueCandidates(source, '', true),
      { initialProps: { source: first.source } },
    );
    await waitFor(() => expect(result.current.values).toEqual(WHOLE.values));
    rerender({ source: second.source });
    expect(result.current.values).toEqual([]);
    expect(result.current.status).toBe('idle');
  });
});

describe('the filter editor offers value candidates', () => {
  function engine() {
    return new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
  }

  it('where the operator compares whole values of a field the data can list', async () => {
    const filter: FilterTree = {
      op: 'and',
      children: [
        { field: 'warehouse', operator: 'EQ', value: '' },
        { field: 'warehouse', operator: 'CONTAINS', value: '' },
        { field: 'status', operator: 'IN', value: [] },
        { field: 'warehouse', operator: 'IS_NULL', value: null },
        { op: 'or', children: [] },
      ],
    };
    const views = engine();
    const { result } = renderHook(() => {
      const opened = useOpenView(views, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    act(() => result.current.opened.runtime!.edit({ filter }));

    const at = (index: number) =>
      result.current.filter.valueCandidates?.([index]) ?? null;
    expect(at(0)?.field).toBe('warehouse');
    // A fragment is typed, not picked.
    expect(at(1)).toBeNull();
    // Not a field the data can list.
    expect(at(2)).toBeNull();
    // No value at all, and a group is no condition.
    expect(at(3)).toBeNull();
    expect(at(4)).toBeNull();
    expect(at(9)).toBeNull();
  });

  it('nowhere on a dashboard, which has no data of its own', () => {
    const board = engine().create('overview', {
      title: 'Board',
      scope: 'personal',
      config: dashboardConfig(),
    });
    expect(board.valueCandidates('warehouse')).toBeNull();
    const { result } = renderHook(() => useFilterEditor(board));
    expect(result.current.valueCandidates?.([0])).toBeNull();
    board.dispose();
  });

  it('on a record view opened fresh', () => {
    const runtime = engine().create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig({
        filter: {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'IN', value: [] }],
        },
      }),
    });
    const { result } = renderHook(() => useFilterEditor(runtime));
    expect(result.current.valueCandidates?.([0])).toBe(
      runtime.valueCandidates('warehouse'),
    );
    runtime.dispose();
  });
});
