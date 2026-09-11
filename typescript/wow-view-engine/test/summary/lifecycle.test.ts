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
  createFilterConfiguration,
  newFilterNode,
} from '../../src/filter/filterCore.js';
import {
  aggregation,
  filter,
  SortDirection,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import type { RecordSummaryFunction } from '../../src/contracts/viewModel.js';
import { deferred, session, setup } from './fixtures.js';

it('refreshes changed metric sets, ignores stale results and disables empty selections', async () => {
  const { engine, source } = setup();
  await engine.load();
  await vi.waitFor(() =>
    expect(session(engine).allSummary.status).toBe('success'),
  );
  const stale = deferred<unknown>();
  source.aggregate
    .mockReturnValueOnce(stale.promise)
    .mockResolvedValueOnce([{ summary0: 9, summary1: 30 }]);
  const setMetrics = (summary: RecordSummaryFunction[]) =>
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setColumns(
        session(engine).instance.config.presentation.table.columns.map(
          column =>
            column.kind === 'field' && column.id === 'amount'
              ? { ...column, summary }
              : column,
        ),
      );
  setMetrics(['SUM', 'AVG']);
  expect(session(engine).pageSummary.values.amount).toEqual({
    SUM: 5,
    AVG: 2.5,
  });
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
  setMetrics(['MAX', 'SUM']);
  expect(source.aggregate.mock.calls[1][2].signal.aborted).toBe(true);
  await vi.waitFor(() =>
    expect(session(engine).allSummary.values.amount).toEqual({
      MAX: 9,
      SUM: 30,
    }),
  );
  stale.resolve([{ summary0: 999, summary1: 999 }]);
  await Promise.resolve();
  expect(session(engine).allSummary.values.amount).toEqual({ MAX: 9, SUM: 30 });
  setMetrics(['SUM', 'MAX']);
  expect(source.aggregate).toHaveBeenCalledTimes(3);
  expect(source.paged).toHaveBeenCalledTimes(1);
  setMetrics([]);
  expect(session(engine).pageSummary.status).toBe('idle');
  expect(session(engine).allSummary.status).toBe('idle');
  engine.dispose();
});

it('loads page and all summaries together, preserving totals across page and presentation changes', async () => {
  const { engine, source } = setup();
  const total = deferred<unknown>();
  source.aggregate.mockReturnValueOnce(total.promise);
  await engine.load();
  expect(session(engine).pageSummary.values).toEqual({ amount: { SUM: 5 } });
  expect(session(engine).allSummary.status).toBe('loading');
  expect(source.aggregate).toHaveBeenCalledOnce();
  engine.record(engine.getSnapshot().selectedInstanceId!).setSelection(['a']);
  total.resolve([{ summary0: 30 }]);
  await vi.waitFor(() =>
    expect(session(engine).allSummary.status).toBe('success'),
  );
  expect(session(engine).allSummary.values).toEqual({ amount: { SUM: 30 } });
  expect(session(engine).selectedRowKeys).toEqual(['a']);
  expect(session(engine).dirty).toBe(false);
  expect(source.paged).toHaveBeenCalledTimes(1);
  expect(source.aggregate.mock.calls[0][0]).toEqual({
    filter: filter.matchAll(),
    metrics: [aggregation.sum(aggregation.field('amount'), 'summary0')],
  });
  const configured = session(engine).instance.config.presentation.table.columns;
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns(
      [...configured]
        .reverse()
        .map(column => ({ ...column, width: 90, visible: column.id === 'id' })),
    );
  await engine.record(engine.getSnapshot().selectedInstanceId!).setPage(2);
  expect(source.aggregate).toHaveBeenCalledTimes(1);
  engine.record(engine.getSnapshot().selectedInstanceId!).setFilterDraft(
    createFilterConfiguration({
      ...newFilterNode(FilterOperator.GTE, 'amount'),
      props: { value: 2 },
    }),
  );
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
  expect(source.aggregate.mock.calls[1][0].filter).toEqual(
    filter.gte('amount', 2),
  );
  engine.dispose();
});

it('ignores old aggregate responses after filtering, removing summaries and disposal', async () => {
  const first = deferred<unknown>();
  const second = deferred<unknown>();
  const third = deferred<unknown>();
  const { engine, source } = setup();
  source.aggregate
    .mockReturnValueOnce(first.promise)
    .mockReturnValueOnce(second.promise)
    .mockReturnValueOnce(third.promise);
  await engine.load();
  engine.record(engine.getSnapshot().selectedInstanceId!).setFilterDraft(
    createFilterConfiguration({
      ...newFilterNode(FilterOperator.GTE, 'amount'),
      props: { value: 10 },
    }),
  );
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
  expect(source.aggregate.mock.calls[0][2].signal.aborted).toBe(true);
  second.resolve([{ summary0: 20 }]);
  await vi.waitFor(() =>
    expect(session(engine).allSummary.values.amount?.SUM).toBe(20),
  );
  first.resolve([{ summary0: 999 }]);
  await Promise.resolve();
  expect(session(engine).allSummary.values.amount?.SUM).toBe(20);
  const refresh = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refreshSummary();
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(3));
  engine.record(engine.getSnapshot().selectedInstanceId!).setColumns(
    session(engine).instance.config.presentation.table.columns.map(column => ({
      ...column,
      summary: undefined,
    })),
  );
  expect(source.aggregate.mock.calls[2][2].signal.aborted).toBe(true);
  expect(session(engine).pageSummary.status).toBe('idle');
  expect(session(engine).allSummary.status).toBe('idle');
  engine.dispose();
  const snapshot = engine.getSnapshot();
  third.resolve([{ summary0: 999 }]);
  await refresh;
  expect(engine.getSnapshot()).toBe(snapshot);
});

it('refreshes changed summary bindings but preserves totals on presentation changes', async () => {
  const { engine, source } = setup();
  await engine.load();
  await vi.waitFor(() =>
    expect(session(engine).allSummary.status).toBe('success'),
  );
  const cols = session(engine).instance.config.presentation.table.columns;
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns(
      cols.map(column =>
        column.id === 'amount' ? { ...column, id: 'amount2' } : column,
      ),
    );
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
  await vi.waitFor(() =>
    expect(session(engine).allSummary.values.amount2?.SUM).toBe(30),
  );
  expect(session(engine).allSummary.values.amount).toBeUndefined();
  expect(source.paged).toHaveBeenCalledTimes(1);
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  await vi.waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(3));
  engine.dispose();
});

it('refreshes changed page and all totals manually but reuses all totals on paging and sorting', async () => {
  const { engine, source } = setup();
  try {
    await engine.load();
    await vi.waitFor(() =>
      expect(session(engine).allSummary.values.amount?.SUM).toBe(30),
    );
    expect(session(engine).pageSummary.values.amount?.SUM).toBe(5);
    source.paged.mockResolvedValue({
      list: [{ id: 'a', amount: 7 }],
      total: 3,
    });
    source.aggregate.mockResolvedValue([{ summary0: 50 }]);

    await engine.record(engine.getSnapshot().selectedInstanceId!).setPage(2);
    await engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setSort([{ field: 'amount', direction: SortDirection.DESC }]);
    expect(session(engine).pageSummary.values.amount?.SUM).toBe(7);
    expect(session(engine).allSummary.values.amount?.SUM).toBe(30);
    expect(source.aggregate).toHaveBeenCalledTimes(1);

    source.paged.mockResolvedValue({
      list: [{ id: 'a', amount: 9 }],
      total: 3,
    });
    await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
    await vi.waitFor(() =>
      expect(session(engine).allSummary.values.amount?.SUM).toBe(50),
    );
    expect(session(engine).pageSummary.values.amount?.SUM).toBe(9);
    expect(source.aggregate).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});
