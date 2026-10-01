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
 * A search or a sort on a view the reader cannot save in place is a look-up
 * nobody meant to keep (R2-39): switching away from it is not asked about —
 * the agent's 「搜索 → 换视图」 loop met the leave question on almost every
 * switch. A condition built on it still is: 「另存为」 is what it was for.
 * A view the reader saves in place keeps asking about all of it.
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  type DataViewDefinition,
  type RecordViewConfig,
  type RecordViewRuntime,
} from '../src/index.js';
import { useWorkbench } from '../src/react/index.js';
import { lookedOnly } from '../src/runtime/navigation.js';
import {
  ordersDefinition,
  recordConfig,
  testSource,
  resourcesOf,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

function searchable(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      {
        name: 'keyword',
        label: 'Search',
        kind: 'search',
        searchFields: ['status'],
        searchMode: 'PHRASE',
      },
    ],
  });
}

function open() {
  const engine = new ViewEngine({
    resources: resourcesOf([searchable()], () => testSource()),
    store: new MemoryViewStore({ instances: [mine] }),
  });
  return renderHook(() => useWorkbench(engine, 'orders', { kinds: ['record'] }))
    .result;
}

const all = systemInstanceId('orders', 'all');

async function opened(result: ReturnType<typeof open>, id: string) {
  act(() => result.current.choose(id));
  await waitFor(() => expect(result.current.openId).toBe(id));
  await waitFor(() => expect(result.current.runtime).not.toBeNull());
  return result.current.runtime as RecordViewRuntime;
}

describe('leaving a look-up', () => {
  it('goes without a question from a search and a sort on a system view', async () => {
    const result = open();
    const runtime = await opened(result, all);

    act(() => {
      runtime.edit({
        sort: [{ field: 'amount', direction: 'DESC' }],
        filter: {
          op: 'and',
          children: [{ field: 'keyword', operator: 'SEARCH', value: 'late' }],
        },
      } as Partial<RecordViewConfig>);
    });
    await waitFor(() => expect(result.current.state?.dirty).toBe(true));

    act(() => result.current.choose('orders-1'));

    expect(result.current.leave.asking).toBe(false);
    await waitFor(() => expect(result.current.openId).toBe('orders-1'));
  });

  it('still asks about a condition built on a system view', async () => {
    const result = open();
    const runtime = await opened(result, all);

    act(() => {
      runtime.edit({
        filter: {
          op: 'and',
          children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
        },
      } as Partial<RecordViewConfig>);
    });
    await waitFor(() => expect(result.current.state?.dirty).toBe(true));

    act(() => result.current.choose('orders-1'));

    expect(result.current.leave.asking).toBe(true);
  });

  it('still asks about a sort on a view the reader saves in place', async () => {
    const result = open();
    const runtime = await opened(result, 'orders-1');

    act(() => {
      runtime.edit({
        sort: [{ field: 'amount', direction: 'DESC' }],
      } as Partial<RecordViewConfig>);
    });
    await waitFor(() => expect(result.current.state?.dirty).toBe(true));

    act(() => result.current.choose(all));

    expect(result.current.leave.asking).toBe(true);
  });
});

describe('lookedOnly', () => {
  const saved = recordConfig();
  it('reads a sort, a page size and the root search as looking', () => {
    expect(
      lookedOnly(
        {
          saved: { config: saved },
          draft: {
            ...saved,
            sort: [{ field: 'amount', direction: 'DESC' }],
            pageSize: 50,
            filter: {
              op: 'and',
              children: [{ field: 'keyword', operator: 'SEARCH', value: 'x' }],
            },
          },
        },
        searchable(),
      ),
    ).toBe(true);
  });

  it('reads anything else as asking, and nothing without a saved config', () => {
    expect(
      lookedOnly(
        { draft: { ...saved, layout: 'card' }, saved: { config: saved } },
        searchable(),
      ),
    ).toBe(false);
    expect(lookedOnly({ draft: saved, saved: null }, searchable())).toBe(false);
    expect(
      lookedOnly(
        {
          saved: { config: saved },
          draft: {
            ...saved,
            filter: {
              op: 'and',
              children: [{ field: 'keyword', operator: 'SEARCH', value: 'x' }],
            },
          },
        },
        ordersDefinition(),
      ),
    ).toBe(false);
  });
});
