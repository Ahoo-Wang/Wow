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
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisSort,
  type AnalysisViewConfig,
  type FilterTree,
  type ViewSource,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { headerSorted } from '../src/ui/analysis/headerSort.js';
import {
  columnWidthOf,
  headerColumnOf,
  readingOf,
} from '../src/ui/analysis/tableColumns.js';
import {
  analysisConfig,
  mine,
  ordersDefinition,
  testSource,
} from './fixtures.js';
import { analysisToggle, openTray } from './fixtures/workbench.js';

afterEach(cleanup);

const asc = (alias: string): AnalysisSort => ({ alias, direction: 'ASC' });
const desc = (alias: string): AnalysisSort => ({ alias, direction: 'DESC' });

/**
 * An analysis's sort decides which groups 「前 N 组」 keeps, so a header's
 * third press cannot simply drop the column as the record table's does — it
 * goes back to the order the presses started from.
 */
describe('the header’s sort cycle', () => {
  it('goes ascending, descending, then back to the view’s order', () => {
    const base = [desc('amount'), asc('warehouse')];

    const first = headerSorted(base, 'orders', base);
    expect(first).toEqual([asc('orders')]);
    const second = headerSorted(first, 'orders', base);
    expect(second).toEqual([desc('orders')]);
    expect(headerSorted(second, 'orders', base)).toEqual(base);
  });

  it('comes back to no order where the view had none', () => {
    expect(headerSorted([desc('orders')], 'orders', [])).toEqual([]);
  });

  /**
   * Where the view already stood on this column descending, descending *is*
   * the way back: the column turns between its two directions rather than
   * spending a press on a sort that changes nothing.
   */
  it('turns between two directions where the view was this column descending', () => {
    const base = [desc('orders')];

    expect(headerSorted(base, 'orders', base)).toEqual([asc('orders')]);
    expect(headerSorted([asc('orders')], 'orders', base)).toEqual(base);
  });

  it('starts another column at ascending, whatever led before', () => {
    expect(headerSorted([desc('orders')], 'amount', [])).toEqual([
      asc('amount'),
    ]);
  });
});

describe('an analysis column’s own width', () => {
  it('reads a metric as a number unless it reads as its field', () => {
    expect(readingOf({ alias: 'n', label: 'N', role: 'metric' })).toBe(
      'number',
    );
    expect(
      readingOf({ alias: 'l', label: 'L', role: 'metric', cell: 'datetime' }),
    ).toBe('datetime');
    expect(readingOf({ alias: 'g', label: 'G', role: 'group' })).toBe('string');
    // A time bucket reads as the day it starts, whatever the field holds.
    expect(
      readingOf({
        alias: 'd',
        label: 'D',
        role: 'group',
        cell: 'number',
        dateUnit: 'DAY',
      }),
    ).toBe('date');
  });

  it('gives a column the room its header and its first values ask for', () => {
    const metric = { alias: 'n', label: 'N', role: 'metric' } as const;
    const group = { alias: 'g', label: 'G', role: 'group' } as const;
    // A floor by reading: a moment needs a date and a time of day.
    const number = columnWidthOf(metric, 'N', ['1']);
    const text = columnWidthOf(group, 'G', ['a']);
    const moment = columnWidthOf({ ...group, cell: 'datetime' }, 'D', ['1']);
    expect(number).toBeLessThan(text);
    expect(text).toBeLessThan(moment);
    // The header is estimated from its characters, a CJK one a full em…
    expect(
      columnWidthOf(metric, '金额 − 成本 − 运费 的 合计', ['1']),
    ).toBeGreaterThan(number);
    // …and so are the values; an id's monospace runs a little smaller.
    const name = 'OrderItemReservedTrackEventProcessor';
    const wide = columnWidthOf(group, 'G', ['a', name]);
    expect(wide).toBeGreaterThan(text);
    expect(
      columnWidthOf({ ...group, cell: 'copyable' }, 'G', [name]),
    ).toBeLessThan(wide);
    // Both up to a ceiling, past which the rest is cut, one hover away.
    expect(columnWidthOf(metric, '很长'.repeat(40), [])).toBe(320);
    expect(columnWidthOf(group, 'G', ['x'.repeat(200)])).toBe(400);
    // A declared width is the width.
    expect(columnWidthOf({ ...group, width: 90 }, 'G', [name])).toBe(90);
  });

  it('hands the record header the alias as the field it sorts', () => {
    expect(
      headerColumnOf(
        { alias: 'total', label: 'Amount', role: 'metric' },
        'Sum of Amount',
        90,
        true,
      ),
    ).toEqual({
      field: 'total',
      label: 'Sum of Amount',
      kind: 'number',
      cell: 'number',
      width: 90,
      sortable: true,
    });
  });
});

/**
 * In the workbench a header press writes `sort` through the analysis editor
 * and runs — a sort is a question member, and a header that answered a press
 * with nothing on screen would look broken, so it runs as the record table's
 * header does rather than waiting for 「改了就跑」 (which a pending range, or
 * auto-run switched off, would hold back). It runs only the sort, though:
 * with anything else in the draft waiting for Apply, the press joins it.
 */
describe('sorting an analysis from its header', () => {
  function open(config: Partial<AnalysisViewConfig> = {}) {
    const source = testSource({
      aggregate: vi.fn(() =>
        Promise.resolve([
          { warehouse: 'CN', orders: 2 },
          { warehouse: 'JP', orders: 1 },
        ]),
      ),
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'orders-1',
            definitionId: 'orders',
            title: 'By warehouse',
            scope: 'personal',
            revision: '1',
            config: analysisConfig(config),
          },
        ],
      }),
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    return { source, engine };
  }

  /** What the open view's draft holds, as the runtime has it. */
  const draftOf = (engine: ViewEngine) =>
    engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;

  /** The sort the last aggregation asked the source for. */
  const asked = (source: ViewSource) =>
    vi.mocked(source.aggregate).mock.lastCall?.[0].sort ?? [];
  const header = (name: string) =>
    screen.getByRole('columnheader', { name: new RegExp(name) });
  const press = (name: string) =>
    fireEvent.click(within(header(name)).getByRole('button'));

  it('runs ascending, descending, then the view’s own order', async () => {
    const { source } = open({ sort: [desc('warehouse')] });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(header('Warehouse').getAttribute('aria-sort')).toBe('descending');

    press('Record count');
    await waitFor(() =>
      expect(asked(source)).toEqual([{ field: 'orders', direction: 'ASC' }]),
    );
    await waitFor(() =>
      expect(header('Record count').getAttribute('aria-sort')).toBe(
        'ascending',
      ),
    );
    expect(header('Warehouse').getAttribute('aria-sort')).toBeNull();

    // Named by its next press: descending, then back to the view's order.
    const named = () =>
      within(header('Record count'))
        .getByRole('button')
        .getAttribute('aria-label');
    expect(named()).toBe('Sort by Record count, descending');
    press('Record count');
    await waitFor(() =>
      expect(header('Record count').getAttribute('aria-sort')).toBe(
        'descending',
      ),
    );
    expect(asked(source)).toEqual([{ field: 'orders', direction: 'DESC' }]);
    expect(named()).toBe('Back to the order before sorting by Record count');

    press('Record count');
    await waitFor(() =>
      expect(header('Warehouse').getAttribute('aria-sort')).toBe('descending'),
    );
    expect(asked(source)).toEqual([{ field: 'warehouse', direction: 'DESC' }]);
    expect(header('Record count').getAttribute('aria-sort')).toBeNull();
  });

  it('adds a column to the order with Shift held', async () => {
    const { source } = open({ sort: [desc('warehouse')] });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    fireEvent.click(within(header('Record count')).getByRole('button'), {
      shiftKey: true,
    });

    await waitFor(() =>
      expect(asked(source)).toEqual([
        { field: 'warehouse', direction: 'DESC' },
        { field: 'orders', direction: 'ASC' },
      ]),
    );
  });

  it('is reached and pressed from the keyboard', async () => {
    const user = userEvent.setup();
    const { source } = open();
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    const first = within(header('Warehouse')).getByRole('button');
    first.focus();
    await user.keyboard('{ArrowRight}');
    const count = within(header('Record count')).getByRole('button');
    expect(document.activeElement).toBe(count);
    await user.keyboard('{Enter}');

    await waitFor(() =>
      expect(asked(source)).toEqual([{ field: 'orders', direction: 'ASC' }]),
    );
  });

  /** Wow refuses a sort over an ungrouped aggregation — one row anyway. */
  it('offers no sort where there is no dimension', async () => {
    open({
      groups: [],
      chart: { type: 'metric', metric: { metric: 'orders' } },
    });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    expect(within(screen.getByRole('table')).queryAllByRole('button')).toEqual(
      [],
    );
  });

  /**
   * The rule the metric card's whole and the funnel repair keep: a gesture
   * on the result never applies an edit it did not make. A range condition
   * waiting for Apply would run along with the sort, so the sort joins it
   * instead — the arrows keep saying the order on screen, the pending dot
   * says there is more to run, and Apply runs the two together.
   */
  it('leaves the run to Apply while the draft holds another edit', async () => {
    const { source, engine } = open({ sort: [desc('warehouse')] });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    const ran = vi.mocked(source.aggregate).mock.calls.length;
    const paid: FilterTree = {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PAID' }],
    };
    act(() => engine.openRuntimes()[0].edit({ filter: paid }));

    press('Record count');
    await waitFor(() => expect(draftOf(engine).sort).toEqual([asc('orders')]));
    // A second press goes on from the sort waiting, not the one on screen.
    press('Record count');
    await waitFor(() => expect(draftOf(engine).sort).toEqual([desc('orders')]));

    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(ran);
    expect(header('Warehouse').getAttribute('aria-sort')).toBe('descending');
    expect(header('Record count').getAttribute('aria-sort')).toBeNull();
    // Each name says what its next press does from what waits, and that it
    // waits: the arrows say what ran (2026-09-23 review P2).
    const named = (name: string) =>
      within(header(name)).getByRole('button').getAttribute('aria-label');
    expect(named('Record count')).toBe(
      'Back to the order before sorting by Record count · descending waits for Apply',
    );
    expect(named('Warehouse')).toBe(
      'Sort by Warehouse, ascending · unsorted waits for Apply',
    );
    expect(
      analysisToggle().querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();

    const tray = await openTray();
    fireEvent.click(within(tray).getByRole('button', { name: /^Apply/ }));
    await waitFor(() =>
      expect(header('Record count').getAttribute('aria-sort')).toBe(
        'descending',
      ),
    );
    const [query] = vi.mocked(source.aggregate).mock.lastCall!;
    expect(query.sort).toEqual([{ field: 'orders', direction: 'DESC' }]);
    expect(JSON.stringify(query)).toContain('PAID');
  });

  /**
   * A sort the tray changed and has not run is not "something else": the
   * press replaces it, so nothing is left waiting and the press runs.
   */
  it('runs over a sort the tray has not run yet, which it replaces', async () => {
    const { source, engine } = open({ sort: [desc('warehouse')] });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    act(() => engine.openRuntimes()[0].edit({ sort: [asc('warehouse')] }));

    press('Record count');

    await waitFor(() =>
      expect(asked(source)).toEqual([{ field: 'orders', direction: 'ASC' }]),
    );
    expect(engine.openRuntimes()[0].getSnapshot().applied).toMatchObject({
      sort: [asc('orders')],
    });
  });

  /** Over a record view there is no analysis sort to write or run. */
  it('writes nothing through a view that is not an analysis', async () => {
    const source = testSource();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, mine.id);
      return { opened, analysis: useAnalysisEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    const before = result.current.opened.runtime!.getSnapshot().draft;

    expect(result.current.analysis.sortNow([asc('orders')])).toBe(false);
    expect(result.current.opened.runtime!.getSnapshot().draft).toBe(before);
  });
});
