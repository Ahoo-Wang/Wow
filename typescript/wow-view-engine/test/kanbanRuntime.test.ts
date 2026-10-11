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

import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordLaneView,
  type RecordViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import { restyledOnly } from '../src/runtime/restyle.js';
import { resourcesOf } from './fixtures.js';
import {
  DEALS,
  dealsDefinition,
  dealsKanban,
  kanbanConfig,
} from './fixtures/kanban.js';

async function openKanban(source: ViewSource = memorySource(DEALS)) {
  const engine = new ViewEngine({
    resources: resourcesOf([dealsDefinition()], () => source),
    store: new MemoryViewStore({ instances: [dealsKanban] }),
  });
  const runtime = (await engine.open(dealsKanban.id)) as RecordViewRuntime;
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().query.status).toBe('success'),
  );
  return runtime;
}

function lanesOf(runtime: RecordViewRuntime): RecordLaneView[] {
  const data = runtime.getSnapshot().result?.data;
  return data?.kind === 'record' ? (data.view.lanes ?? []) : [];
}

function keys(lane: RecordLaneView | undefined) {
  return lane?.rows.map(row => row.key);
}

describe('a kanban run', () => {
  it('asks every lane its first page and counts the option lanes in one aggregation', async () => {
    const runtime = await openKanban();
    const [lead, won, lost, other] = lanesOf(runtime);
    expect(keys(lead)).toEqual(['d-1', 'd-2']);
    expect(lead).toMatchObject({ total: 3, next: { cursor: '2' } });
    expect(keys(won)).toEqual(['d-4']);
    expect(won).toMatchObject({ total: 1, next: null });
    expect(lost).toMatchObject({ total: 0, rows: [] });
    // Neither a stage the options list, nor any stage at all.
    expect(keys(other)).toEqual(['d-5', 'd-6']);
    expect(other).toMatchObject({ value: null, total: null });
    expect(runtime.getSnapshot().result?.data.issues).toEqual([]);
    runtime.dispose();
  });

  it('keeps the cards when the counts fail, and says what is missing', async () => {
    const memory = memorySource(DEALS);
    const runtime = await openKanban({
      ...memory,
      aggregate: () => Promise.reject(new Error('no counts here')),
    });
    const [lead] = lanesOf(runtime);
    expect(keys(lead)).toEqual(['d-1', 'd-2']);
    expect(lead?.total).toBeNull();
    expect(runtime.getSnapshot().result?.data.issues).toEqual([
      expect.objectContaining({
        code: 'runtime.kanban.counts-unavailable',
        severity: 'warning',
      }),
    ]);
    runtime.dispose();
  });

  it("puts one lane's next page after it, quietly, and leaves the rest", async () => {
    const runtime = await openKanban();
    const before = lanesOf(runtime);
    runtime.loadLane(before[0]!.id);
    // Asked without the board turning to 「正在查询」.
    expect(runtime.getSnapshot().query.status).toBe('success');
    await vi.waitFor(() =>
      expect(keys(lanesOf(runtime)[0])).toEqual(['d-1', 'd-2', 'd-3']),
    );
    const after = lanesOf(runtime);
    expect(after[0]).toMatchObject({ total: 3, next: null });
    expect(after[1]).toBe(before[1]);
    runtime.dispose();
  });

  it('asks nothing for a lane with nothing more', async () => {
    const source = memorySource(DEALS);
    const cursor = vi.spyOn(source, 'cursor');
    const runtime = await openKanban(source);
    const calls = cursor.mock.calls.length;
    runtime.loadLane(lanesOf(runtime)[1]!.id);
    runtime.loadLane('nope');
    expect(cursor.mock.calls.length).toBe(calls);
    runtime.dispose();
  });

  it('starts every lane over on a refresh', async () => {
    const runtime = await openKanban();
    runtime.loadLane(lanesOf(runtime)[0]!.id);
    await vi.waitFor(() => expect(keys(lanesOf(runtime)[0])).toHaveLength(3));
    runtime.refresh();
    await vi.waitFor(() =>
      expect(keys(lanesOf(runtime)[0])).toEqual(['d-1', 'd-2']),
    );
    runtime.dispose();
  });

  it('runs a switch into a table as a page again', async () => {
    const runtime = await openKanban();
    runtime.edit({ layout: 'table' });
    runtime.apply();
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().result?.own.layout).toBe('table'),
    );
    const data = runtime.getSnapshot().result?.data;
    expect(data?.kind === 'record' && data.view.lanes).toBeUndefined();
    runtime.dispose();
  });
});

describe('restyledOnly under a kanban', () => {
  const table = kanbanConfig({ layout: 'table' });

  it('calls a switch into or out of the kanban a question, and table to cards drawn', () => {
    expect(restyledOnly(table, { ...table, layout: 'kanban' })).toBeNull();
    expect(restyledOnly({ ...table, layout: 'kanban' }, table)).toBeNull();
    expect(restyledOnly(table, { ...table, layout: 'card' })).toBe('drawn');
  });

  it("calls its lanes a question, but not a table's stored half", () => {
    const kanban = kanbanConfig();
    expect(
      restyledOnly(kanban, {
        ...kanban,
        kanban: { lane: 'stage', hidden: ['WON'] },
      }),
    ).toBeNull();
    expect(
      restyledOnly(table, { ...table, kanban: { lane: 'stage', hidden: [] } }),
    ).toBe('drawn');
  });

  it('runs its summaries with its lanes', () => {
    const kanban = kanbanConfig();
    expect(
      restyledOnly(kanban, {
        ...kanban,
        summaries: [{ field: 'amount', fn: 'SUM' }],
      }),
    ).toBeNull();
  });
});
