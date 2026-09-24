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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
  type AnalysisView,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  AnalysisTable,
  DataWorkbench,
  ViewSurface,
  defaultMessages,
  zhCN,
} from '../src/ui/index.js';
import type { DataViewKind } from '../src/ui/index.js';
import { groupText } from '../src/ui/analysis/DrillMenu.js';
import { useViewMessages } from '../src/ui/MessagesProvider.js';
import {
  analysisConfig,
  mine,
  namedOrdersDefinition,
  ordersDefinition,
  testSource,
} from './fixtures.js';
import {
  analysisToggle,
  editorToggle,
  openTray,
} from './fixtures/workbench.js';

afterEach(cleanup);

/** The analysis view every case opens: one warehouse group, rows as a table. */
const chart: ViewInstance = {
  id: 'orders-chart',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({ layout: 'table' }),
};

/**
 * A definition with a second dimension to split by: the orders fixture
 * declares only `warehouse` as groupable, and the one group already in force
 * is never on offer.
 */
function twoDimensions(): DataViewDefinition {
  return ordersDefinition({
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'status',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

function open(
  options: {
    kinds?: readonly DataViewKind[];
    definition?: DataViewDefinition;
    source?: ViewSource;
    instance?: ViewInstance;
    environment?: RuntimeEnvironment;
  } = {},
) {
  const source = options.source ?? testSource();
  const instance = options.instance ?? chart;
  const engine = new ViewEngine({
    ...(options.environment ? { environment: options.environment } : {}),
    definitions: [options.definition ?? ordersDefinition()],
    // A record view beside the analysis one, so the list holds both kinds.
    store: new MemoryViewStore({ instances: [mine, instance] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId={instance.id}
      kinds={options.kinds ?? ['record', 'analysis']}
      locale="en-GB"
    />,
  );
  return { engine, source };
}

/** The line under the title bar of a view opened out of another. */
const originBar = () =>
  waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="origin-bar"]',
    );
    if (!found) throw new Error('no origin bar');
    return found;
  });

/** The band that says what the rows on screen were fetched under. */
const appliedBar = () =>
  waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    );
    if (!found) throw new Error('no applied bar');
    return found;
  });

/** The analysis result's one data row: the group the user presses. */
async function groupRow(): Promise<HTMLElement> {
  const table = await screen.findByRole('table');
  return await waitFor(() => {
    const row = within(table).getAllByRole('row')[1];
    if (!row) throw new Error('the result has no group row yet');
    return row;
  });
}

const menu = () =>
  document.querySelector<HTMLElement>('[data-slot="drill-menu"]');

const item = (name: string) => screen.findByRole('menuitem', { name });

/** The one open analysis runtime, for reading what is actually in force. */
function analysisRuntime(engine: ViewEngine) {
  const found = engine
    .openRuntimes()
    .find(runtime => runtime.kind === 'analysis');
  if (!found) throw new Error('no analysis view is open');
  return found;
}

const appliedIn = (runtime: ReturnType<typeof analysisRuntime>) =>
  runtime.getSnapshot().applied as AnalysisViewConfig;

describe('the follow-up menu on one group', () => {
  /**
   * The pointer presses a mark; the keyboard presses a row. A chart's marks
   * are inside an element that says `role="img"` and holds nothing focusable
   * (`charts/EChart.tsx`'s `chart-plot`), and the reading table beside it is `sr-only` and
   * deliberately out of reach — so the table layout is where the same menu is
   * opened without a pointer (F10), and the row says it opens one.
   */
  it('opens from a group row on a press, on Enter and on Space', async () => {
    open();
    const row = await groupRow();

    expect(row.tabIndex).toBe(0);
    expect(row.getAttribute('aria-haspopup')).toBe('menu');
    expect(row.hasAttribute('data-pickable')).toBe(true);
    expect(menu()).toBeNull();

    fireEvent.click(row);
    await waitFor(() => expect(menu()).not.toBeNull());
    // The menu is about one group, and says which: the row's conditions, in
    // the applied bar's own words.
    expect(
      menu()!.querySelector('[data-slot="drill-group"]')!.textContent,
    ).toBe('Warehouse is CN');
    fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(menu()).toBeNull());

    for (const key of ['Enter', ' ']) {
      fireEvent.keyDown(await groupRow(), { key });
      await waitFor(() => expect(menu()).not.toBeNull());
      fireEvent.keyDown(document.body, { key: 'Escape' });
      await waitFor(() => expect(menu()).toBeNull());
    }
  });

  it('closes on Escape, putting the user back on the row they pressed', async () => {
    const { engine, source } = open();
    const row = await groupRow();
    fireEvent.keyDown(row, { key: 'Enter' });
    await waitFor(() => expect(menu()).not.toBeNull());
    const ran = source.aggregate as unknown as { mock: { calls: unknown[] } };
    const before = ran.mock.calls.length;

    fireEvent.keyDown(document.body, { key: 'Escape' });

    await waitFor(() => expect(menu()).toBeNull());
    expect(ran.mock.calls.length).toBe(before);
    expect(analysisRuntime(engine).getSnapshot().dirty).toBe(false);
    expect(screen.queryByRole('table')).not.toBeNull();
    // The menu has no control of its own to hand focus back to, so it hands
    // it back to what was pressed: a keyboard user closing the menu is where
    // they were, not at the top of the document.
    await waitFor(() => expect(document.activeElement).toBe(row));
  });

  /**
   * The records view says each thing once (2026-09-23 audit): its name is
   * what it is, the definition's records of the group; the origin bar is
   * the way back, naming the origin; the group's conditions are on the
   * applied bar, with the editor folded rather than unfolded over them.
   */
  it('opens the records behind the group, named by what they are, the conditions said once', async () => {
    open();
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.records']));

    const bar = await originBar();
    expect(bar.textContent).toBe('Back to By warehouse');
    expect(
      within(bar).getByRole('button', { name: 'Back to By warehouse' }),
    ).toBeDefined();
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: 'Orders · Warehouse is CN',
      }),
    ).toBeDefined();
    const applied = await appliedBar();
    await waitFor(() =>
      expect(within(applied).getByText('Warehouse is CN')).toBeDefined(),
    );
    expect(screen.getAllByText('Warehouse is CN')).toHaveLength(1);
    expect(editorToggle().getAttribute('aria-expanded')).toBe('false');
    await waitFor(() => expect(menu()).toBeNull());
  });

  it('offers no records where no record view can be made', async () => {
    open({ kinds: ['analysis'] });
    fireEvent.click(await groupRow());
    await waitFor(() => expect(menu()).not.toBeNull());

    expect(
      screen.queryByRole('menuitem', {
        name: defaultMessages['label.drill.records'],
      }),
    ).toBeNull();
    // The two that edit this view are still there: they are nobody's to
    // permit, and they need no second view.
    expect(
      screen.getByRole('menuitem', {
        name: defaultMessages['label.drill.focus'],
      }),
    ).toBeDefined();
  });

  /**
   * 「只看这一组」 is a question of its own beside the one it came from
   * (2026-09-23 audit): it used to write the group into this view's range
   * and run, with no way back but undoing it by hand. Now the narrowed
   * question opens as an unsaved view, named by what it is, with the same
   * way back as the records — and going back is the result as it was, not
   * a run.
   */
  it('asks the question of the group alone, beside this one, with the way back', async () => {
    const { engine, source } = open();
    const row = await groupRow();
    const saved = analysisRuntime(engine);
    fireEvent.click(row);
    fireEvent.click(await item(defaultMessages['label.drill.focus']));

    // Asked once more — by the new view, not by this one.
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
    expect(saved.getSnapshot().dirty).toBe(false);
    expect(appliedIn(saved).filter).toEqual({ op: 'and', children: [] });
    const followed = engine
      .openRuntimes()
      .find(runtime => runtime !== saved && runtime.kind === 'analysis')!;
    expect(appliedIn(followed).filter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
    // Still grouped as it was: only the range moved.
    expect(appliedIn(followed).groups.map(group => group.field)).toEqual([
      'warehouse',
    ]);

    // Named by what it is, the conditions on the applied bar alone, the tray
    // folded, and the way back naming where it came from.
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: 'By warehouse · Warehouse is CN',
      }),
    ).toBeDefined();
    const applied = await appliedBar();
    await waitFor(() =>
      expect(within(applied).getByText('Warehouse is CN')).toBeDefined(),
    );
    expect(analysisToggle().getAttribute('aria-expanded')).toBe('false');
    const bar = await originBar();
    expect(bar.textContent).toBe('Back to By warehouse');

    fireEvent.click(
      within(bar).getByRole('button', { name: 'Back to By warehouse' }),
    );

    // The result it came from, as it was: no run, nothing to save.
    await waitFor(() =>
      expect(document.querySelector('[data-slot="origin-bar"]')).toBeNull(),
    );
    expect(
      screen.getByRole('heading', { level: 2, name: 'By warehouse' }),
    ).toBeDefined();
    expect(source.aggregate).toHaveBeenCalledTimes(2);
    expect(followed.disposed).toBe(true);
    expect(saved.getSnapshot().dirty).toBe(false);
  });

  /**
   * The name says the group only while the group is in force (2026-09-23
   * review P2): taking its chip off the applied bar used to leave the title
   * — and 「另存为」 prefilled from it — naming a group the view no longer
   * shows.
   */
  it('stops naming the group once its chip is taken off the applied bar', async () => {
    open();
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.focus']));
    await screen.findByRole('heading', {
      level: 2,
      name: 'By warehouse · Warehouse is CN',
    });

    const applied = await appliedBar();
    fireEvent.click(
      await within(applied).findByRole('button', {
        name: 'Unset Warehouse is CN',
      }),
    );

    await screen.findByRole('heading', { level: 2, name: 'By warehouse' });
    fireEvent.click(
      screen.getByRole('button', { name: defaultMessages['label.save.save'] }),
    );
    const named = await screen.findByRole('textbox', {
      name: defaultMessages['label.save.title'],
    });
    expect((named as HTMLInputElement).value).toBe('By warehouse');
  });

  /**
   * A date bucket reads as its column prints it (2026-09-23 audit): the
   * heading used to be its condition, the two instants bounding the month
   * written out in full. The records it opens are named the same way.
   */
  it('names a date bucket as the table reads it, not as the range behind it', async () => {
    // A bucket's key is where it starts, on the engine's clock.
    const month = Date.UTC(2026, 8, 1);
    const monthly: ViewInstance = {
      ...chart,
      id: 'orders-monthly',
      title: 'By month',
      config: analysisConfig({
        layout: 'table',
        groups: [
          {
            alias: 'month',
            field: 'createdAt',
            type: 'DATE_HISTOGRAM',
            unit: 'MONTH',
          },
        ],
        table: { columns: [] },
        chart: {
          type: 'bar',
          cartesian: { x: 'month', series: [{ metric: 'orders' }] },
        },
      }),
    };
    open({
      definition: namedOrdersDefinition(),
      instance: monthly,
      source: testSource({
        aggregate: vi.fn(() => Promise.resolve([{ month, orders: 2 }])),
      }),
      environment: defaultRuntimeEnvironment({ timeZone: 'UTC' }),
    });
    const bucket = new Intl.DateTimeFormat('en-GB', {
      year: 'numeric',
      month: 'long',
      calendar: 'gregory',
      timeZone: 'UTC',
    }).format(month);

    const row = (await groupRow()) as HTMLTableRowElement;
    // The same words the table cell holds.
    expect(row.cells[0]!.textContent).toBe(bucket);
    fireEvent.click(row);
    await waitFor(() => expect(menu()).not.toBeNull());
    expect(
      menu()!.querySelector('[data-slot="drill-group"]')!.textContent,
    ).toBe(`Created in ${bucket}`);

    fireEvent.click(await item(defaultMessages['label.drill.records']));
    await originBar();
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: `Orders · Created in ${bucket}`,
      }),
    ).toBeDefined();
    // The records' applied bar says the same condition in the same words
    // (2026-09-23 review P2): one reading, where it used to be the two
    // instants bounding the month.
    const applied = await appliedBar();
    await waitFor(() =>
      expect(within(applied).getByText(`Created in ${bucket}`)).toBeDefined(),
    );
  });

  it('splits the group by a dimension it is not grouped by already', async () => {
    const { engine, source } = open({ definition: twoDimensions() });
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.split']));

    // The dimensions on offer are the groupable fields this analysis does
    // not already group by — never the one the pressed group is of.
    await item('Status');
    const split = document.querySelector<HTMLElement>(
      '[data-slot="dropdown-menu-sub-content"]',
    )!;
    expect(
      within(split)
        .getAllByRole('menuitem')
        .map(entry => entry.textContent),
    ).toEqual(['Status']);

    const saved = analysisRuntime(engine);
    fireEvent.click(within(split).getByRole('menuitem', { name: 'Status' }));

    // The same question, of the group the user pressed, by the other
    // dimension: one group, and the range narrowed to the row — asked as a
    // view of its own beside this one, which stays as it ran (the user's
    // 2026-09-23 ruling: all three follow-ups open beside).
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
    const followed = engine
      .openRuntimes()
      .find(runtime => runtime !== saved && runtime.kind === 'analysis')!;
    const applied = appliedIn(followed);
    expect(applied.groups.map(group => group.field)).toEqual(['status']);
    expect(applied.filter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
    await waitFor(() => expect(menu()).toBeNull());
    expect(saved.getSnapshot().dirty).toBe(false);
    expect(appliedIn(saved).groups.map(group => group.field)).toEqual([
      'warehouse',
    ]);
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: 'By warehouse · Warehouse is CN',
      }),
    ).toBeDefined();

    // Back is the result it came from, without a run.
    const bar = await originBar();
    fireEvent.click(within(bar).getByRole('button'));
    await waitFor(() =>
      expect(document.querySelector('[data-slot="origin-bar"]')).toBeNull(),
    );
    expect(source.aggregate).toHaveBeenCalledTimes(2);
    expect(followed.disposed).toBe(true);
    expect(analysisRuntime(engine)).toBe(saved);
  });

  /**
   * Both lists are the kernel's `groupableFields`: the tray's over the
   * draft, the split's over the config that ran. With nothing edited the
   * two configs are one, so the two menus must offer the same fields.
   */
  it('offers to split by exactly the fields the tray would add a dimension on', async () => {
    open({ definition: twoDimensions() });
    const names = (root: HTMLElement) =>
      within(root)
        .getAllByRole('menuitem')
        .map(entry => entry.textContent);

    await openTray();
    fireEvent.click(screen.getByRole('button', { name: 'Add dimension' }));
    const added = names(await screen.findByRole('menu'));
    fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());

    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.split']));
    await item('Status');
    const split = document.querySelector<HTMLElement>(
      '[data-slot="dropdown-menu-sub-content"]',
    )!;

    expect(names(split)).toEqual(added);
  });

  /**
   * The menu is as wide as its words and hangs from what was pressed
   * (2026-09-23 audit). It used to be anchored to the whole row, and the
   * popup's recipe sizes a menu to its anchor (`--anchor-width`), so it came
   * out as wide as the table — a band across the result rather than a menu
   * by the pointer. Its pixels are measured in a browser
   * (`FollowUpMenuFitsItsWords`); this pins what is handed to it.
   */
  it('hangs from the cell pressed, or the row’s first cell for a key — never the row', () => {
    const view: AnalysisView = {
      columns: [
        { alias: 'warehouse', label: 'Warehouse', role: 'group' },
        { alias: 'orders', label: 'Orders', role: 'metric' },
      ],
      rows: [
        { warehouse: 'CN', orders: 3 },
        { warehouse: 'JP', orders: 2 },
      ],
      truncated: false,
    };
    const onPick = vi.fn();
    render(<AnalysisTable view={view} onPick={onPick} />);
    const row =
      document.querySelectorAll<HTMLTableRowElement>('tr[data-pickable]')[1]!;

    // A press on a cell: the menu hangs from that cell, and the keyboard
    // goes back to the row it belongs to.
    fireEvent.click(within(row).getByText('2'));
    expect(onPick).toHaveBeenLastCalledWith(view.rows[1], row.cells[1], row);

    // A key has no point: the row's first cell, so the menu opens under the
    // start of the group's name rather than across the row.
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(onPick).toHaveBeenLastCalledWith(view.rows[1], row.cells[0], row);
    for (const [, anchor] of onPick.mock.calls) expect(anchor).not.toBe(row);
  });

  it('sizes the menu to its words, not to what it hangs from', async () => {
    open();
    fireEvent.click(await groupRow());
    await waitFor(() => expect(menu()).not.toBeNull());
    // Surviving class assertion: a length with no state behind it. The
    // popup's recipe carries `w-(--anchor-width)`; the menu's own width
    // replaces it, and what that measures is `FollowUpMenuFitsItsWords`.
    expect(menu()!.className).toMatch(/\bw-auto\b/);
    expect(menu()!.className).not.toContain('w-(--anchor-width)');
  });

  /**
   * The one reading `groupText` adds to the applied bar's: a bucket as its
   * column prints it. A week is printed as the day it starts, so the words
   * say it is a week; a bucket with no key — the sentinel — has no date to
   * print and reads as its condition, as any other dimension does.
   */
  it('reads a week as a week, and a keyless bucket as its condition', () => {
    const messages = renderHook(() => useViewMessages()).result.current;
    const display = { locale: 'en-GB', timeZone: 'UTC' };
    const column = {
      alias: 'week',
      label: 'Created',
      role: 'group' as const,
      dateUnit: 'WEEK' as const,
    };
    const start = Date.UTC(2026, 8, 21);
    // The week's range, as `describeFilter` reads the drill's condition.
    const week = [
      {
        path: [0],
        text: 'Created between …',
        unresolved: false,
        field: 'createdAt',
        label: 'Created',
        operator: 'BETWEEN' as const,
        value: {
          kind: 'period' as const,
          unit: 'WEEK' as const,
          from: new Date(start).toISOString(),
          timeZone: 'UTC',
        },
      },
    ];
    const conditions = [
      {
        path: [0],
        text: 'Created is empty',
        unresolved: false,
        field: 'createdAt',
        label: 'Created',
        operator: 'IS_NULL' as const,
      },
    ];
    const day = new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'medium',
      timeZone: 'UTC',
    }).format(start);

    expect(
      groupText({ column, value: start, conditions: week }, messages, display),
    ).toBe(`Created in the week of ${day}`);
    const keyless = groupText(
      { column, value: null, conditions },
      messages,
      display,
    );
    expect(keyless).not.toContain('week');
    expect(keyless.startsWith('Created')).toBe(true);
    // A dimension no column was drawn for reads as its conditions too.
    expect(
      groupText(
        { column: undefined, value: start, conditions },
        messages,
        display,
      ),
    ).toBe(keyless);
  });

  /**
   * A number band is named as its row reads — 「单价 在 ¥0～500」 — rather
   * than as the two comparisons behind it, whose bounds print in full.
   */
  it('names a number band as its row reads it', () => {
    const messages = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <ViewSurface messages={zhCN} locale="zh-CN">
          {children}
        </ViewSurface>
      ),
    }).result.current;
    const display = { locale: 'zh-CN' };
    const column = {
      alias: 'band',
      label: '单价',
      role: 'group' as const,
      kind: 'number',
      cell: 'number',
      numberFormat: { style: 'currency' as const, currency: 'CNY' },
      interval: 500,
    };
    const conditions = [
      {
        path: [0],
        text: '单价 ≥ ¥0.00',
        unresolved: false,
        field: 'price',
        label: '单价',
        operator: 'GTE' as const,
      },
    ];

    expect(groupText({ column, value: 0, conditions }, messages, display)).toBe(
      '单价 在 ¥0～500',
    );
    // A named dimension still says its field: the view opened from it has
    // no 「价格区间」 column, and its conditions are on 单价 — the same rule
    // a date bucket's heading keeps.
    expect(
      groupText(
        {
          column: { ...column, label: '价格区间', named: true },
          value: 500,
          conditions,
        },
        messages,
        display,
      ),
    ).toBe('单价 在 ¥500～1,000');
  });
});
