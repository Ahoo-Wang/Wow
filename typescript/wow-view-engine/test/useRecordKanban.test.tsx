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
  MAX_KANBAN_LANES,
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import type { CursorPage } from '@ahoo-wang/wow-client/dsl';
import {
  useOpenView,
  useRecordKanban,
  useRecordTable,
} from '../src/react/index.js';
import { memorySource } from '../src/testing/index.js';
import { deferred, resourcesOf } from './fixtures.js';
import {
  DEALS,
  STAGES,
  dealsDefinition,
  dealsKanban,
  kanbanConfig,
} from './fixtures/kanban.js';
import { FilterOperator } from '@ahoo-wang/wow-client/dsl';

afterEach(cleanup);

const PRIORITIES = [
  { value: 'P1', label: 'Urgent' },
  { value: 'P2', label: 'Normal' },
];

function definition() {
  return dealsDefinition({
    fields: [
      { name: 'id', label: 'Deal', kind: 'string', sortable: true },
      { name: 'stage', label: 'Stage', kind: 'enum', options: STAGES },
      {
        name: 'priority',
        label: 'Priority',
        kind: 'enum',
        options: PRIORITIES,
      },
      { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
    ],
  });
}

async function openHook(
  instance: ViewInstance = dealsKanban,
  source: ViewSource = memorySource(DEALS),
) {
  const engine = new ViewEngine({
    resources: resourcesOf([definition()], () => source),
    store: new MemoryViewStore({ instances: [instance] }),
  });
  const { result } = renderHook(() => {
    const opened = useOpenView(engine, instance.id);
    const runtime = opened.runtime as RecordViewRuntime | null;
    return {
      runtime,
      table: useRecordTable(runtime),
      kanban: useRecordKanban(runtime),
    };
  });
  await waitFor(() => expect(result.current.table.status).toBe('success'));
  return result;
}

describe('useRecordKanban', () => {
  it('hands on the lanes that ran, the lane fields and the options of the one in use', async () => {
    const result = await openHook();
    const { kanban } = result.current;
    expect(kanban.lanes.map(lane => lane.label)).toEqual([
      'Lead',
      'Won',
      'Lost',
      '',
    ]);
    expect(kanban.laneFields.map(field => field.name)).toEqual([
      'stage',
      'priority',
    ]);
    expect(kanban.laneOptions).toBe(STAGES);
    expect(kanban.spec).toEqual({ lane: 'stage' });
    expect(kanban.maxLanes).toBe(MAX_KANBAN_LANES);
  });

  it('runs a lane folded away, and puts it back as the JSON it was', async () => {
    const result = await openHook();
    act(() => result.current.kanban.setKanban({ hidden: ['WON'] }));
    await waitFor(() =>
      expect(result.current.kanban.lanes.map(lane => lane.label)).toEqual([
        'Lead',
        'Lost',
        '',
      ]),
    );
    act(() => result.current.kanban.setKanban({ hidden: [] }));
    expect(result.current.runtime?.getSnapshot().draft.kanban).toEqual({
      lane: 'stage',
    });
    expect(result.current.runtime?.getSnapshot().dirty).toBe(false);
  });

  it("starts a new lane field with its own lanes, not the last one's folds", async () => {
    const result = await openHook({
      ...dealsKanban,
      config: kanbanConfig({ kanban: { lane: 'stage', hidden: ['WON'] } }),
    });
    act(() => result.current.kanban.setKanban({ lane: 'priority' }));
    expect(result.current.kanban.spec).toEqual({ lane: 'priority' });
    expect(result.current.kanban.laneOptions).toBe(PRIORITIES);
  });

  it('waits for Apply while a condition waits, like every one-press edit', async () => {
    const result = await openHook();
    const runtime = result.current.runtime!;
    act(() =>
      runtime.edit({
        filter: {
          op: 'and',
          children: [
            { field: 'amount', operator: FilterOperator.GT, value: 10 },
          ],
        },
      }),
    );
    act(() => result.current.kanban.setKanban({ hidden: ['LOST'] }));
    expect(runtime.getSnapshot().applied.kanban).toEqual({ lane: 'stage' });
  });

  it('spins the lane loading more until its cards land, and only that one', async () => {
    const memory = memorySource(DEALS);
    const held = deferred<CursorPage<RecordData>>();
    let holding = false;
    const result = await openHook(dealsKanban, {
      ...memory,
      cursor: (query, options) =>
        holding ? held.promise : memory.cursor(query, options),
    });
    const [lead, won] = result.current.kanban.lanes;
    holding = true;
    // A lane with nothing more asks nothing, and spins nothing.
    act(() => result.current.kanban.loadLane(won!.id));
    expect(result.current.kanban.loadingLane).toBeNull();
    act(() => result.current.kanban.loadLane(lead!.id));
    expect(result.current.kanban.loadingLane).toBe(lead!.id);
    await act(async () => {
      held.resolve({ list: [{ id: 'd-3', stage: 'LEAD' }], nextCursor: null });
      await held.promise;
    });
    await waitFor(() => expect(result.current.kanban.loadingLane).toBeNull());
    expect(result.current.kanban.lanes[0]?.rows).toHaveLength(3);
  });
});

describe('useRecordKanban without a runtime', () => {
  it('has no lanes, and its commands do nothing', () => {
    const { result } = renderHook(() => useRecordKanban(null));
    expect(result.current).toMatchObject({
      lanes: [],
      loadingLane: null,
      spec: null,
      laneFields: [],
      laneOptions: [],
    });
    act(() => result.current.loadLane('option:"LEAD"'));
    act(() => result.current.setKanban({ hidden: ['WON'] }));
    expect(result.current.spec).toBeNull();
  });

  it('writes nothing for a definition with no lane field', async () => {
    const none = {
      ...dealsKanban,
      config: kanbanConfig({ layout: 'table', kanban: undefined }),
    };
    const engine = new ViewEngine({
      resources: resourcesOf(
        [
          dealsDefinition({
            fields: [
              { name: 'id', label: 'Deal', kind: 'string', sortable: true },
            ],
            record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
          }),
        ],
        () => memorySource(DEALS),
      ),
      store: new MemoryViewStore({ instances: [none] }),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, none.id);
      const runtime = opened.runtime as RecordViewRuntime | null;
      return { runtime, kanban: useRecordKanban(runtime) };
    });
    await waitFor(() => expect(result.current.runtime).not.toBeNull());
    act(() => result.current.kanban.setKanban({ lane: 'stage' }));
    expect(result.current.runtime?.getSnapshot().draft.kanban).toBeUndefined();
  });
});

describe("useRecordTable's switch into a kanban", () => {
  it('writes the definition’s kanban half in and runs it', async () => {
    const result = await openHook({
      ...dealsKanban,
      config: kanbanConfig({ layout: 'table', kanban: undefined }),
    });
    act(() => result.current.table.setLayout('kanban'));
    expect(result.current.runtime?.getSnapshot().draft.kanban).toEqual({
      lane: 'stage',
    });
    await waitFor(() =>
      expect(result.current.kanban.lanes.length).toBeGreaterThan(0),
    );
  });

  it('runs a switch back out of it', async () => {
    const result = await openHook();
    act(() => result.current.table.setLayout('card'));
    await waitFor(() =>
      expect(result.current.runtime?.getSnapshot().result?.own.layout).toBe(
        'card',
      ),
    );
    expect(result.current.kanban.lanes).toEqual([]);
  });
});
