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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench, RecordTable } from '../src/ui/index.js';
import { withoutFirstUnavailable } from '../src/runtime/unavailable.js';
import {
  analysisConfig,
  deferred,
  nextTask,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { recordTableController } from './fixtures/ui.js';
import {
  describedField,
  notModified,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

afterEach(cleanup);

/** A saved view that sorts by amount and keeps the pending orders. */
const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending by amount',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        { field: 'status', operator: 'EQ', value: 'PENDING' },
        { field: 'warehouse', operator: 'EQ', value: 'CN' },
      ],
    },
    sort: [{ field: 'amount', direction: 'DESC' }],
  }),
};

/** Orders on a store that neither filters by status nor sorts by amount. */
function narrower(version = 'sha256:orders-narrow'): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    version,
    fields: base.fields.map(field =>
      field.path === 'status'
        ? describedField('status', { filter: { operators: [] } })
        : field.path === 'amount'
          ? describedField('amount', { sort: { paged: false, cursor: false } })
          : field,
    ),
  };
}

function harness(
  answers: QueryModelDescriptor[],
  options: {
    definition?: DataViewDefinition;
    source?: Partial<ViewSource>;
    instances?: ViewInstance[];
  } = {},
) {
  const describe = vi.fn();
  for (const answer of answers) describe.mockResolvedValueOnce(read(answer));
  describe.mockImplementation((version?: string) =>
    Promise.resolve(notModified(version ?? '')),
  );
  const source = testSource({ describe, ...options.source });
  const clock = testEnvironment();
  const onError = vi.fn();
  const engine = new ViewEngine({
    definitions: [options.definition ?? ordersDefinition()],
    store: new MemoryViewStore({ instances: options.instances ?? [pending] }),
    resolveSource: () => source,
    environment: { ...clock.environment, onError },
  });
  return { engine, source, describe, clock, onError };
}

async function openData(
  engine: ViewEngine,
  id: string,
): Promise<ViewRuntime<DataViewConfig>> {
  const runtime = await engine.open(id);
  if (runtime.kind === 'dashboard') throw new Error('a data view');
  return runtime;
}

describe('a saved view using what the source no longer admits (Q2)', () => {
  it('is flagged, sends nothing, and is fixed with one press', async () => {
    const { engine, source } = harness([narrower()]);

    const runtime = await openData(engine, 'pending');
    await nextTask();

    const codes = runtime.unavailable().map(found => found.code);
    expect(codes).toEqual(
      expect.arrayContaining([
        'filter.operator.unsupported',
        'record.sort.not-sortable',
      ]),
    );
    expect(source.paged).not.toHaveBeenCalled();

    runtime.removeUnavailable();
    const { draft, issues } = runtime.getSnapshot();
    expect(draft).toMatchObject({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
      sort: [],
    });
    expect(issues.filter(found => found.severity === 'error')).toEqual([]);
    expect(runtime.unavailable()).toEqual([]);
    // Taken out of the draft; nothing runs until it is applied.
    expect(source.paged).not.toHaveBeenCalled();
    runtime.apply();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('leaves an ordinary mistake alone: only what the source changed is unavailable', async () => {
    const broken: ViewInstance = {
      ...pending,
      id: 'broken',
      config: recordConfig({
        sort: [{ field: 'status', direction: 'ASC' }],
      }),
    };
    const { engine } = harness([narrower()], { instances: [broken] });

    const runtime = await openData(engine, 'broken');

    // `status` was never sortable: that is the view's own mistake.
    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'record.sort.not-sortable',
    );
    expect(runtime.unavailable()).toEqual([]);
    runtime.removeUnavailable();
    expect(runtime.getSnapshot().draft).toEqual(broken.config);
  });

  it('offers the press in the workbench, and takes the conditions out', async () => {
    const { engine } = harness([narrower()]);
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="pending"
      />,
    );

    const press = await screen.findByRole('button', {
      name: 'Remove unavailable conditions',
    });
    expect(
      screen.getByText(
        'This view uses what its data source no longer offers, and waits until it is removed',
      ),
    ).toBeTruthy();
    fireEvent.click(press);

    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove unavailable conditions' }),
      ).toBeNull(),
    );
    const runtime = engine.openRuntimes()[0];
    expect(runtime.getSnapshot().draft).toMatchObject({ sort: [] });
  });
});

describe('an open view when the descriptor changes version', () => {
  it('takes the new narrowing at once, and waits when its config no longer runs', async () => {
    const { engine, source, describe, clock } = harness([ordersDescriptor()]);
    const runtime = await openData(engine, 'pending');
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
    expect(runtime.unavailable()).toEqual([]);

    const answer = deferred<ReturnType<typeof read>>();
    describe.mockReturnValueOnce(answer.promise);
    clock.advance(5 * 60 * 1000);
    act(() => runtime.refresh());
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(2);
    answer.resolve(read(narrower('sha256:orders-2')));
    await nextTask();

    // The result stays on screen; what it was asked with is flagged.
    expect(runtime.getSnapshot().result).not.toBeNull();
    expect(runtime.unavailable().length).toBeGreaterThan(0);
    // Nothing more is sent for it, by a press of refresh or by the clock.
    runtime.refresh();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(2);
  });

  it('refuses the view when the new descriptor contradicts its definition', async () => {
    const contradicting = {
      ...ordersDescriptor(),
      version: 'sha256:orders-cursor-only',
      record: { ...ordersDescriptor().record, paging: ['CURSOR'] as never },
    };
    const { engine, source, describe, clock } = harness([ordersDescriptor()]);
    const runtime = await openData(engine, 'system:orders:all');
    await nextTask();
    describe.mockResolvedValueOnce(read(contradicting));
    clock.advance(5 * 60 * 1000);
    runtime.refresh();
    await nextTask();
    const asked = vi.mocked(source.paged).mock.calls.length;

    expect(runtime.getSnapshot().issues[0]).toMatchObject({
      code: 'view.definition.invalid',
    });
    runtime.refresh();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(asked);
  });
});

describe('an entry that needs a condition (Q3)', () => {
  const counted = (): QueryModelDescriptor => ({
    ...ordersDescriptor(),
    constraints: [{ type: 'COUNT_REQUIRES_FILTER' }],
  });

  it('sends nothing for a view without one, and runs once one is added', async () => {
    const bare: ViewInstance = {
      ...pending,
      id: 'bare',
      config: recordConfig(),
    };
    const { engine, source } = harness([counted()], { instances: [bare] });

    const runtime = await openData(engine, 'bare');
    await nextTask();

    expect(runtime.getSnapshot().issues).toContainEqual({
      code: 'record.filter.required',
      severity: 'error',
      path: [],
    });
    expect(source.paged).not.toHaveBeenCalled();
    // Not something the source changed under a saved view: nothing to remove.
    expect(runtime.unavailable()).toEqual([]);

    runtime.edit({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });
    runtime.apply();
    await nextTask();
    expect(source.paged).toHaveBeenCalledTimes(1);
  });

  it('says so in the table, with the way to add one', () => {
    const onEmptyAction = vi.fn();
    render(
      <RecordTable
        table={recordTableController({
          hasResult: false,
          status: 'idle',
          filterRequired: true,
        })}
        emptyWayOut="add"
        onEmptyAction={onEmptyAction}
      />,
    );

    expect(screen.getByText('Add a condition first')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));
    expect(onEmptyAction).toHaveBeenCalledTimes(1);
  });

  it('draws the empty state where the rows would be in the workbench, and no error', async () => {
    const { engine } = harness([counted()], {
      instances: [{ ...pending, id: 'bare', config: recordConfig() }],
    });
    render(
      <DataWorkbench engine={engine} definitionId="orders" instanceId="bare" />,
    );

    expect(await screen.findByText('Add a condition first')).toBeTruthy();
    expect(
      screen.queryByText('This view needs fixing before it runs'),
    ).toBeNull();
  });

  it('asks nothing of a cursor source, which counts nothing', async () => {
    const { engine, source } = harness([counted()], {
      definition: ordersDefinition({
        record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
      }),
      instances: [{ ...pending, id: 'bare', config: recordConfig() }],
    });

    const runtime = await openData(engine, 'bare');
    await nextTask();

    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(source.cursor).toHaveBeenCalledTimes(1);
  });
});

/** A Wow rejection of a cursor it cannot read, as fetcher's `ExchangeError` carries it. */
function invalidCursor() {
  const body = { errorCode: 'IllegalArgument', errorMsg: 'Invalid cursor.' };
  return Object.assign(new Error('Request failed with status code 400'), {
    exchange: {
      response: { status: 400 },
      extractResult: () => Promise.resolve(body),
    },
  });
}

describe('a cursor-paged view (#3502)', () => {
  const cursorOrders = ordersDefinition({
    record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
  });

  function cursorHarness(cursor: ViewSource['cursor']) {
    const onError = vi.fn();
    const engine = new ViewEngine({
      definitions: [cursorOrders],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource({ cursor }),
      environment: { ...testEnvironment().environment, onError },
    });
    return { engine, onError };
  }

  it('starts over from the first page when its sort or conditions change', async () => {
    const cursor = vi.fn((query: { cursor?: string | null }) => {
      void query;
      return Promise.resolve({ nextCursor: 'next', list: [] });
    });
    const { engine } = cursorHarness(cursor as never);
    const runtime = engine.create('orders', {
      title: 'Orders',
      scope: 'personal',
      config: recordConfig(),
    });
    await nextTask();
    if (!('page' in runtime)) throw new Error('a record view');
    runtime.page({ cursor: 'next' });
    await nextTask();
    expect(cursor).toHaveBeenLastCalledWith(
      expect.objectContaining({ cursor: 'next' }),
      undefined,
      expect.anything(),
    );

    runtime.edit({ sort: [{ field: 'amount', direction: 'DESC' }] });
    runtime.apply();
    await nextTask();
    expect(cursor.mock.lastCall?.[0]).not.toHaveProperty('cursor', 'next');

    runtime.page({ cursor: 'next' });
    await nextTask();
    runtime.edit({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });
    runtime.apply();
    await nextTask();
    expect(cursor.mock.lastCall?.[0]).not.toHaveProperty('cursor', 'next');
  });

  it('falls back to the first page once when the source cannot read its cursor', async () => {
    const cursor = vi.fn((query: { cursor?: string | null }) =>
      query.cursor === 'stale'
        ? Promise.reject(invalidCursor())
        : Promise.resolve({ nextCursor: 'more', list: [] }),
    );
    const { engine, onError } = cursorHarness(cursor as never);
    const runtime = engine.create('orders', {
      title: 'Orders',
      scope: 'personal',
      config: recordConfig(),
    });
    await nextTask();
    if (!('page' in runtime)) throw new Error('a record view');

    runtime.page({ cursor: 'stale' });
    await nextTask();
    await nextTask();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(onError).not.toHaveBeenCalled();
    expect(cursor.mock.lastCall?.[0]).not.toHaveProperty('cursor', 'stale');
  });

  it('says the failure when the first page is refused too', async () => {
    const cursor = vi.fn(() => Promise.reject(invalidCursor()));
    const { engine, onError } = cursorHarness(cursor);
    const runtime = engine.create('orders', {
      title: 'Orders',
      scope: 'personal',
      config: recordConfig(),
    });
    await nextTask();
    await nextTask();

    // A first page carries no cursor to fall back from: the failure is said.
    expect(runtime.getSnapshot().query.status).toBe('error');
    expect(onError).toHaveBeenCalledTimes(1);
    expect(cursor).toHaveBeenCalledTimes(1);
  });
});

describe('what 「移除不可用的条件」 takes out of an analysis', () => {
  const unavailable = (path: (string | number)[], params = {}) => ({
    code: 'x',
    severity: 'error' as const,
    path,
    params,
  });
  const base = analysisConfig({
    groups: [
      {
        alias: 'warehouse',
        field: 'warehouse',
        type: 'TERMS',
        missingKey: '(empty)',
      },
    ],
    metrics: [
      {
        alias: 'orders',
        type: 'COUNT',
        filter: {
          op: 'and',
          children: [
            { field: 'status', operator: 'EQ', value: 'PENDING' },
            { field: 'warehouse', operator: 'EQ', value: 'CN' },
          ],
        },
      },
    ],
    having: { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 1 },
  });

  it('a dimension loses its missing-value group, and keeps its field', () => {
    const next = withoutFirstUnavailable(base, [
      unavailable(['groups', 0, 'missingKey']),
    ]);
    expect(next?.groups[0]).toEqual({
      alias: 'warehouse',
      field: 'warehouse',
      type: 'TERMS',
    });
  });

  it('「只保留」 goes whole, and a metric loses the conditions on a field', () => {
    expect(
      withoutFirstUnavailable(base, [unavailable(['having', 'metric'])]),
    ).not.toHaveProperty('having');
    const next = withoutFirstUnavailable(base, [
      unavailable(['metrics', 0, 'filter'], { field: 'status' }),
    ]);
    expect(next?.metrics[0]).toMatchObject({
      filter: {
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });
  });

  it('a metric or a dimension itself stays, for the reader to change', () => {
    expect(
      withoutFirstUnavailable(base, [unavailable(['metrics', 0, 'field'])]),
    ).toBeNull();
    expect(
      withoutFirstUnavailable(base, [unavailable(['groups', 0, 'field'])]),
    ).toBeNull();
    expect(
      withoutFirstUnavailable(recordConfig(), [unavailable(['children'])]),
    ).toBeNull();
  });
});
