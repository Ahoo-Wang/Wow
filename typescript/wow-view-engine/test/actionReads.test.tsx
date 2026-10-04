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
 * A declared action's rule that reads a field its rows did not fetch, told
 * in development (`runtime/actionReads.ts`). A row brings only what the view
 * needs (`recordProjection`); 「发货」 reading the status on a table that
 * hides the status column reads nothing, and the action greys out on every
 * row without a word. In a development build `onIssue` hears it once per
 * view and field, with the field, the action and `record.rowFields`; any
 * other build makes no proxy and tells nothing.
 */

import { types } from 'node:util';
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  actions,
  MemoryViewStore,
  ViewEngine,
  type Issue,
  type RecordAction,
  type RecordRow,
  type RecordViewConfig,
  type RecordViewRuntime,
} from '../src/index.js';
import {
  watchActionReads,
  watchedActions,
} from '../src/runtime/actionReads.js';
import { issueHint } from '../src/runtime/failure/issueReport.js';
import { DataWorkbench, en, zhCN } from '../src/ui/index.js';
import {
  mine,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

/** 「Ship」 reads the status, which `mine` neither shows nor asks for. */
function ship(seen: RecordRow[] = []): RecordAction {
  return {
    id: 'ship',
    label: 'Ship',
    primary: true,
    available: row => {
      seen.push(row);
      return row.data.status === 'SHIPPED' ? 'Already shipped' : true;
    },
    run: () => Promise.resolve(),
  };
}

/** A workbench on `mine` with `declared`, its engine telling `onIssue`. */
async function open(
  declared: readonly RecordAction[],
  view = mine,
  definition = ordersDefinition(),
) {
  const onIssue = vi.fn<(found: Issue) => void>();
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => testSource()),
    store: new MemoryViewStore({ instances: [view] }),
    onIssue,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId={view.id}
      record={{ actions: actions(declared) }}
    />,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  const told = () =>
    onIssue.mock.calls
      .map(([found]) => found)
      .filter(found => found.code === 'record.action.unfetched');
  return { told };
}

const shipButton = (key: string) =>
  within(
    screen
      .getAllByRole('row')
      .find(row => within(row).queryByLabelText(`Select ${key}`))!,
  ).getByRole('button', { name: 'Ship' });

describe('in a development build', () => {
  it('tells onIssue once of a field an action reads that the rows do not fetch', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    const { told } = await open([ship()]);

    await waitFor(() => expect(told()).toHaveLength(1));
    expect(told()[0]).toEqual({
      code: 'record.action.unfetched',
      severity: 'warning',
      path: [],
      params: {
        action: 'ship',
        field: 'status',
        definition: 'orders',
        view: 'orders-1',
      },
    });
    // The rule decides exactly as without the proxy: this source hands the
    // whole record, so the shipped order is still refused.
    expect(shipButton('o-2').getAttribute('aria-disabled')).toBe('true');
    expect(shipButton('o-1').getAttribute('aria-disabled')).not.toBe('true');
  });

  it('tells of one field once for the view, whichever action reads it and however often', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    const { told } = await open([
      ship(),
      {
        id: 'hold',
        label: 'Hold',
        available: row => row.data.status !== 'SHIPPED' || 'Gone',
        run: () => Promise.resolve(),
      },
    ]);

    await waitFor(() => expect(told()).toHaveLength(1));
    expect(told()[0]?.params?.action).toBe('ship');
  });

  it('tells nothing of a field the rows fetch: shown, or named in rowFields', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    const shown = await open([ship()], {
      ...mine,
      config: recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'status' }] },
      }),
    });
    expect(shown.told()).toEqual([]);
    cleanup();

    const named = await open(
      [ship()],
      mine,
      ordersDefinition({
        record: {
          rowKey: 'id',
          paging: 'paged',
          layouts: ['table', 'card'],
          rowFields: ['status'],
        },
      }),
    );
    expect(named.told()).toEqual([]);
  });

  it('points the host at record.rowFields, in both languages and on the console', () => {
    expect(en['record.action.unfetched']).toContain('record.rowFields');
    expect(zhCN['record.action.unfetched']).toContain('record.rowFields');
    expect(issueHint('record.action.unfetched')).toContain('record.rowFields');
  });
});

describe('in any other build', () => {
  it('makes no proxy and tells nothing', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    const seen: RecordRow[] = [];
    const { told } = await open([ship(seen)]);

    expect(seen.length).toBeGreaterThan(0);
    expect(seen.some(row => types.isProxy(row.data))).toBe(false);
    expect(told()).toEqual([]);
  });
});

describe('the proxy a rule reads a row through', () => {
  /** A runtime as the watch reads it: its definition, config and identity. */
  function runtime(applied: RecordViewConfig, definition = nested()) {
    return {
      id: 'runtime-1',
      definition,
      getSnapshot: () => ({ applied, saved: null }),
    } as unknown as RecordViewRuntime;
  }

  /** A definition with an object and an array of objects. */
  function nested() {
    return ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string' },
        { name: 'state.status', label: 'Status', kind: 'string' },
        { name: 'state.retries', label: 'Retries', kind: 'number' },
        {
          name: 'lines',
          label: 'Lines',
          kind: 'array',
          elementTitle: 'sku',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            { name: 'qty', label: 'Qty', kind: 'number' },
          ],
        },
      ],
      record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
    });
  }

  const ROW: RecordRow = {
    key: 'o-1',
    data: {
      id: 'o-1',
      state: { status: 'PENDING', retries: 2 },
      lines: [{ sku: 'A', qty: 1 }],
    },
  };

  /** What `rule` reads off `ROW`, and the fields it was told of. */
  function read(
    columns: string[],
    rule: (row: RecordRow) => unknown,
  ): { answer: unknown; told: string[] } {
    vi.stubEnv('NODE_ENV', 'development');
    const found: Issue[] = [];
    const watched = runtime(
      recordConfig({ table: { columns: columns.map(field => ({ field })) } }),
    );
    watchActionReads(watched, issue => found.push(issue), new Set());
    let answer: unknown;
    const [action] = watchedActions(
      actions([
        {
          id: 'look',
          label: 'Look',
          available: row => {
            answer = rule(row);
            return true;
          },
          run: () => Promise.resolve(),
        },
      ]),
      watched,
      [ROW],
    )!;
    action!.available!(ROW, { now: 0 });
    return {
      answer,
      told: found.map(issue => String(issue.params?.field)),
    };
  }

  it('reads a nested field the rows fetch as it is, and tells of its unfetched sibling', () => {
    expect(
      read(['id', 'state.status'], row => [
        (row.data.state as { status: string }).status,
        (row.data.state as { retries: number }).retries,
      ]),
    ).toEqual({ answer: ['PENDING', 2], told: ['state.retries'] });
  });

  it('tells of an element field the rows bring only the title of', () => {
    expect(
      read(['id', 'lines'], row =>
        (row.data.lines as { sku: string; qty: number }[]).map(
          line => `${line.sku}×${line.qty}`,
        ),
      ),
    ).toEqual({ answer: ['A×1'], told: ['lines.qty'] });
  });

  it('tells nothing of what is no field: an array’s own members, an object’s methods', () => {
    expect(
      read(['id', 'lines'], row => [
        (row.data.lines as unknown[]).length,
        String(row.data),
        'nothing' in row.data,
      ]),
    ).toEqual({ answer: [1, '[object Object]', false], told: [] });
  });

  it('hands the rules a row that is not on the page — the detail’s, read whole — as it is', () => {
    vi.stubEnv('NODE_ENV', 'development');
    const watched = runtime(recordConfig());
    watchActionReads(watched, () => {}, new Set());
    const seen: RecordRow[] = [];
    const [action] = watchedActions(actions([ship(seen)]), watched, [])!;
    action!.available!(ROW, { now: 0 });

    expect(seen).toEqual([ROW]);
    expect(seen[0]).toBe(ROW);
  });

  it('leaves the actions as declared where the runtime is not watched', () => {
    vi.stubEnv('NODE_ENV', 'production');
    const watched = runtime(recordConfig());
    watchActionReads(watched, () => {}, new Set());
    const declared = actions([ship()]);

    expect(watchedActions(declared, watched, [ROW])).toBe(declared);
  });
});
