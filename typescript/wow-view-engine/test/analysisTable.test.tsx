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
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AnalysisView } from '../src/index.js';
import { AnalysisTable, ViewSurface, zhCN } from '../src/ui/index.js';
import { columnWidthOf } from '../src/ui/analysis/tableColumns.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

describe('AnalysisTable', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'warehouse', label: 'Warehouse', role: 'group' },
      {
        alias: 'orders',
        label: 'Orders',
        role: 'metric',
        numberFormat: { style: 'decimal' },
        width: 120,
      },
    ],
    rows: [
      { warehouse: 'CN', orders: 2 },
      { warehouse: 'JP', orders: null },
    ],
    truncated: false,
  };

  it('renders the rows and formats the numbers', () => {
    render(<AnalysisTable view={view} />);

    expect(screen.getByText('CN')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  it('puts the totals row in the footer', () => {
    render(<AnalysisTable view={{ ...view, totals: { orders: 3 } }} />);

    expect(screen.getByText('Total')).toBeDefined();
    expect(screen.getByText('3')).toBeDefined();
  });

  it('renders every value shape a row can hold', () => {
    render(
      <AnalysisTable
        view={{
          columns: [
            { alias: 'warehouse', label: 'Warehouse', role: 'group' },
            { alias: 'plain', label: 'Plain', role: 'metric' },
            { alias: 'flag', label: 'Flag', role: 'metric' },
            { alias: 'blob', label: 'Blob', role: 'metric' },
          ],
          rows: [
            {
              warehouse: 'CN',
              plain: 7,
              flag: false,
              blob: { nested: true },
            },
          ],
          truncated: false,
        }}
      />,
    );

    expect(screen.getByText('7')).toBeDefined();
    expect(screen.getByText('No')).toBeDefined();
    expect(screen.getByText('{"nested":true}')).toBeDefined();
  });

  it('shows a date bucket as the day it starts, and an enum key by its label', () => {
    const day = Date.UTC(2026, 8, 18);
    render(
      <ViewSurface locale="en-GB" timeZone="UTC">
        <AnalysisTable
          view={{
            columns: [
              {
                alias: 'day',
                label: 'Day',
                role: 'group',
                kind: 'datetime',
                cell: 'datetime',
                dateUnit: 'DAY',
              },
              {
                alias: 'status',
                label: 'Status',
                role: 'group',
                kind: 'enum',
                cell: 'enum',
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
              { alias: 'orders', label: 'Orders', role: 'metric' },
            ],
            rows: [{ day, status: 'FAILED', orders: 2 }],
            truncated: false,
          }}
        />
      </ViewSurface>,
    );

    expect(
      screen.getByText(
        new Intl.DateTimeFormat('en-GB', {
          dateStyle: 'medium',
          timeZone: 'UTC',
        }).format(day),
      ),
    ).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  /**
   * A number histogram's key is only its band's lower bound: 「¥0.00」 does
   * not say which band a row is (2026-09-23, real backend). The row says the
   * band, short, in the surface's language.
   */
  it('shows a number histogram key as the band it starts', () => {
    const band = {
      alias: 'band',
      label: '单价',
      role: 'group' as const,
      kind: 'number',
      cell: 'number',
      numberFormat: { style: 'currency' as const, currency: 'CNY' },
      interval: 500,
    };
    render(
      <ViewSurface messages={zhCN} locale="zh-CN">
        <AnalysisTable
          view={{
            columns: [
              band,
              { alias: 'orders', label: '订单数', role: 'metric' },
            ],
            rows: [
              { band: 0, orders: 2 },
              { band: 500, orders: 1 },
            ],
            truncated: false,
          }}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('¥0～500')).toBeDefined();
    expect(screen.getByText('¥500～1,000')).toBeDefined();
    expect(screen.queryByText('¥0.00')).toBeNull();
  });

  /**
   * The sentence is about the range, not about the analysis: "nothing to
   * aggregate" read as "this analysis computes nothing", which is never what
   * happened — the metrics are fine and no group matched.
   */
  it('says that no group matched', () => {
    render(<AnalysisTable view={{ ...view, rows: [] }} />);

    expect(screen.getByText('No groups match')).toBeDefined();
  });
});

/**
 * A metric column is titled by two parts the kernel hands over separately —
 * the field and the summary — because only a catalogue knows their order.
 * The alias used to be the only thing that told two summaries of one field
 * apart, and the alias is not a word anybody chose.
 */
describe('an analysis column header', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'wh', label: 'Warehouse', role: 'group' },
      { alias: 'm1', label: 'Amount', role: 'metric', fn: 'SUM' },
      { alias: 'm2', label: 'Amount', role: 'metric', fn: 'AVG' },
      { alias: 'm3', label: 'orders', role: 'metric', fn: 'COUNT' },
    ],
    rows: [{ wh: 'CN', m1: 10, m2: 5, m3: 2 }],
    truncated: false,
  };

  it('says the summary of the field, and tells two summaries apart', () => {
    render(<AnalysisTable view={view} />);

    const headers = screen
      .getAllByRole('columnheader')
      .map(cell => cell.textContent);
    expect(headers).toEqual([
      'Warehouse',
      'Sum of Amount',
      'Average of Amount',
      // A count counts records rather than summarising a field, so it is one
      // word and never the alias the query carried.
      'Record count',
    ]);
  });

  it('says the same two parts in the other language, in its own order', () => {
    render(
      <ViewSurface messages={zhCN} locale="zh-CN">
        <AnalysisTable view={view} />
      </ViewSurface>,
    );

    expect(
      screen.getAllByRole('columnheader').map(cell => cell.textContent),
    ).toEqual(['Warehouse', 'Amount的总和', 'Amount的平均', '记录数']);
  });
});

/**
 * What an aggregate is decides how it prints, not what it was computed from
 * (K5) — and it prints in the surface's language, not the machine's.
 */
describe('an analysis number', () => {
  const money = { style: 'currency', currency: 'CNY' } as const;

  it('reads a metric in its own format and the surface language', () => {
    render(
      <ViewSurface locale="zh-CN">
        <AnalysisTable
          view={{
            columns: [
              { alias: 'wh', label: 'Warehouse', role: 'group' },
              {
                alias: 'total',
                label: 'Amount',
                role: 'metric',
                fn: 'SUM',
                numberFormat: money,
              },
              {
                alias: 'orders',
                label: 'orders',
                role: 'metric',
                fn: 'COUNT',
                numberFormat: { maximumFractionDigits: 0 },
              },
              { alias: 'plain', label: 'Plain', role: 'metric', fn: 'AVG' },
            ],
            rows: [{ wh: 'CN', total: 1234, orders: 1200, plain: 1234.5 }],
            truncated: false,
          }}
        />
      </ViewSurface>,
    );

    // `zh-CN` writes the yuan sign; the machine's own language writes CN¥.
    expect(screen.getByText('¥1,234.00')).toBeDefined();
    // A count is grouped and whole, in nobody's currency.
    expect(screen.getByText('1,200')).toBeDefined();
    // A number with no declared format is still grouped.
    expect(screen.getByText('1,234.5')).toBeDefined();
  });
});

/**
 * Three numbers that mean something other than what they look like (D20
 * 口径), each said by the screen rather than left to a document: the totals
 * row's scope, a percentile's approximation, and the value «any value» does
 * not promise. The third lives on the metric card — `test/analysisTray.test.tsx`
 * 「the tray’s metric cards」 — and only the first two are on this table.
 */
describe('the three readings a result says out loud', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'wh', label: 'Warehouse', role: 'group' },
      { alias: 'p95', label: 'Latency', role: 'metric', fn: 'PERCENTILE' },
      { alias: 'total', label: 'Amount', role: 'metric', fn: 'SUM' },
    ],
    rows: [{ wh: 'CN', p95: 120, total: 30 }],
    truncated: false,
  };

  /** The cell carrying the word «Total», which is the one that is read. */
  function heading(): HTMLElement {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="totals-heading"]',
    );
    if (!found) throw new Error('no totals heading');
    return found;
  }

  it('says the totals row covers the whole range, not the rows above it', () => {
    render(<AnalysisTable view={{ ...view, totals: { total: 900 } }} />);

    expect(document.querySelector('[data-slot="totals-row"]')).not.toBeNull();
    // Said where the word is, and seen: a quiet line under 「Total」, part of
    // the cell's own text, so a reader meets it with the cell and nobody has
    // to find a `title` to learn it.
    expect(heading().textContent).toBe('TotalEvery record in the range');
    expect(
      heading().querySelector('[data-slot="totals-scope"]')?.textContent,
    ).toBe('Every record in the range');
    expect(heading().title).toBe('');
    expect(heading().getAttribute('aria-describedby')).toBeNull();
    // Text, not a control: the table with no action to take gains no stop.
    expect(heading().querySelector('button, [tabindex]')).toBeNull();
    expect(heading().hasAttribute('tabindex')).toBe(false);
  });

  it('says it in the surface language too', () => {
    render(
      <ViewSurface messages={zhCN} locale="zh-CN">
        <AnalysisTable view={{ ...view, totals: { total: 900 } }} />
      </ViewSurface>,
    );

    expect(
      heading().querySelector('[data-slot="totals-scope"]')?.textContent,
    ).toBe('范围内全部记录');
  });

  /**
   * Wow computes percentiles approximately. The sign travels with the header
   * — an axis title and a legend read the same `columnTitle` — and the word
   * behind it sits on the header cell.
   */
  it('marks a percentile header approximate, and nothing else', () => {
    render(<AnalysisTable view={view} />);

    const headers = screen.getAllByRole('columnheader');
    expect(headers.map(cell => cell.textContent)).toEqual([
      'Warehouse',
      '≈ Percentile of Latency',
      'Sum of Amount',
    ]);
    expect(headers[1].dataset.note).toBe('');
    expect(describedText(headers[1])).toBe('Approximate');
    // An exact sum beside it wears neither the sign nor the word.
    expect(headers[2].dataset.note).toBeUndefined();
    expect(describedText(headers[2])).toBe('');
  });
});

/**
 * A9. The pressable rows are a column of peers, so they are one Tab stop
 * with the arrows inside it (`ui/roving.ts`, the same group the record
 * view's header row is). A hundred groups used to be a hundred stops
 * between the toolbar and whatever follows the table, which a keyboard
 * leaving the result had to walk one group at a time.
 */
describe('the result rows are one tab stop', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'warehouse', label: 'Warehouse', role: 'group' },
      { alias: 'orders', label: 'Orders', role: 'metric' },
    ],
    rows: [
      { warehouse: 'CN', orders: 3 },
      { warehouse: 'JP', orders: 2 },
      { warehouse: 'US', orders: 1 },
    ],
    truncated: false,
  };

  const rows = () => [
    ...document.querySelectorAll<HTMLElement>('tr[data-pickable]'),
  ];
  const stops = () => rows().map(row => row.getAttribute('tabindex'));

  it('gives the group one stop, wherever the keyboard is in it', () => {
    render(<AnalysisTable view={view} onPick={() => {}} />);

    expect(stops()).toEqual(['0', '-1', '-1']);

    // Focus is what the stop follows, however it got there.
    fireEvent.focus(rows()[2]!);
    expect(stops()).toEqual(['-1', '-1', '0']);
  });

  it('moves between the rows on the arrows, and stops at the ends', () => {
    render(<AnalysisTable view={view} onPick={() => {}} />);
    rows()[0]!.focus();

    fireEvent.keyDown(rows()[0]!, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(rows()[1]);
    expect(stops()).toEqual(['-1', '0', '-1']);

    fireEvent.keyDown(rows()[1]!, { key: 'End' });
    expect(document.activeElement).toBe(rows()[2]);

    // The end does not wrap: past the last row lies whatever follows the
    // table, and a group that sends a reader back to row one has no way out.
    fireEvent.keyDown(rows()[2]!, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(rows()[2]);

    fireEvent.keyDown(rows()[2]!, { key: 'Home' });
    expect(document.activeElement).toBe(rows()[0]);
    expect(stops()).toEqual(['0', '-1', '-1']);
  });

  it('opens the follow-up menu on Enter and Space, as a press does', () => {
    const onPick = vi.fn();
    render(<AnalysisTable view={view} onPick={onPick} />);

    fireEvent.keyDown(rows()[1]!, { key: 'Enter' });
    fireEvent.keyDown(rows()[1]!, { key: ' ' });
    fireEvent.click(rows()[1]!);

    expect(onPick).toHaveBeenCalledTimes(3);
    expect(onPick.mock.calls.every(call => call[0] === view.rows[1])).toBe(
      true,
    );
    // A modifier belongs to the browser or the page: Ctrl+Home is the top
    // of the document, not the top of this table.
    fireEvent.keyDown(rows()[1]!, { key: 'Home', ctrlKey: true });
    expect(document.activeElement).toBe(document.body);
  });

  /** Rows nothing can be asked of take no stop at all. */
  it('leaves an unpressable result out of the Tab order', () => {
    render(<AnalysisTable view={view} />);

    expect(rows()).toHaveLength(0);
    expect(
      [...document.querySelectorAll('tbody tr')].map(row =>
        row.getAttribute('tabindex'),
      ),
    ).toEqual([null, null, null]);
  });
});

/**
 * An analyst's table reads its numbers from the right edge, in figures of
 * one width, header included — the record table's recipe (`NUMERIC_CELL`),
 * said on the cell as `data-numeric`. A dimension, and a metric that reads
 * as its field's dates, stay on the left. Whether the pixels are right is
 * measured in the browser (`AnalysisWorkbench` 回归 `TableReadsLikeATable`).
 */
describe('an analysis number reads from the right', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'wh', label: 'Warehouse', role: 'group' },
      { alias: 'total', label: 'Amount', role: 'metric', fn: 'SUM' },
      {
        alias: 'latest',
        label: 'Created',
        role: 'metric',
        fn: 'MAX',
        kind: 'datetime',
        cell: 'datetime',
      },
    ],
    rows: [{ wh: 'CN', total: 30, latest: Date.UTC(2026, 8, 18) }],
    totals: { total: 30, latest: Date.UTC(2026, 8, 18) },
    truncated: false,
  };

  const numeric = (cells: Iterable<Element>) =>
    [...cells].map(cell => (cell as HTMLElement).dataset.numeric === '');

  it('puts a metric on the right, header, rows and totals alike', () => {
    render(<AnalysisTable view={view} />);

    // The filler that ends each row is no column (`aria-hidden`).
    const columns = (row: Element) =>
      row.querySelectorAll(':scope > :not([aria-hidden])');
    expect(numeric(screen.getAllByRole('columnheader'))).toEqual([
      false,
      true,
      false,
    ]);
    expect(numeric(columns(document.querySelector('tbody tr')!))).toEqual([
      false,
      true,
      false,
    ]);
    expect(
      numeric(columns(document.querySelector('[data-slot="totals-row"]')!)),
    ).toEqual([false, true, false]);
  });
});

/**
 * A dimension over an identifier — a field the record view reads as a value
 * to copy (`cell: 'copyable'`) — shows its values in that reading's
 * monospace, so `0` and `O` do not look alike in an analysis either.
 */
describe('an identifier dimension', () => {
  it('shows its values in the monospace an id is read in', () => {
    render(
      <AnalysisTable
        view={{
          columns: [
            {
              alias: 'order',
              label: 'Order',
              role: 'group',
              kind: 'string',
              cell: 'copyable',
            },
            { alias: 'city', label: 'City', role: 'group', kind: 'string' },
            { alias: 'orders', label: 'Orders', role: 'metric', fn: 'COUNT' },
          ],
          rows: [
            { order: 'SO-1001', city: 'Hangzhou', orders: 1 },
            { order: null, city: 'Ningbo', orders: 1 },
          ],
          truncated: false,
        }}
      />,
    );

    const ids = [...document.querySelectorAll('[data-slot="identifier"]')];
    expect(ids.map(node => node.textContent)).toEqual(['SO-1001']);
    // Surviving class assertion: the face is a declaration with no state
    // behind it — the record table's own `IDENTIFIER_FACE`, which the
    // browser story measures as a monospace family.
    expect(ids[0]!.className).toContain('font-mono');
  });
});

/**
 * A column is as wide as the column says, never as its widest value: an
 * automatic layout moved every column after one whose answer grew, so the
 * column the eye was on was elsewhere after each question. The widths come
 * from the table's declaration, else the column's reading and header
 * (`tableColumns.ts`) — and the rows do not enter into it.
 */
describe('an analysis column keeps its width', () => {
  const columns: AnalysisView['columns'] = [
    { alias: 'wh', label: 'Warehouse', role: 'group' },
    { alias: 'orders', label: 'Orders', role: 'metric', fn: 'COUNT' },
    { alias: 'total', label: 'Amount', role: 'metric', fn: 'SUM', width: 120 },
  ];
  const first: AnalysisView = {
    columns,
    rows: [{ wh: 'CN', orders: 2, total: 30 }],
    truncated: false,
  };
  const widths = () =>
    screen
      .getAllByRole('columnheader')
      .map(cell => [
        cell.style.width,
        cell.style.minWidth,
        cell.style.maxWidth,
      ]);

  it('takes the declared width, and holds every width on its cells', () => {
    render(<AnalysisTable view={first} />);

    const [group, count, amount] = widths();
    expect(amount).toEqual(['120px', '120px', '120px']);
    // A width with a floor and a ceiling is a width, not a suggestion.
    expect(new Set(group).size).toBe(1);
    expect(new Set(count).size).toBe(1);
    expect(group[0]).not.toBe('');
    // Each body cell carries its column's width as well.
    expect(document.querySelector<HTMLElement>('tbody td')!.style.width).toBe(
      group[0],
    );
  });

  it('does not move when the answer changes', () => {
    const { rerender } = render(<AnalysisTable view={first} />);
    const before = widths();

    rerender(
      <AnalysisTable
        view={{
          columns,
          rows: [
            {
              wh: 'OrderItemReservedTrackEventProcessor',
              orders: 1234567,
              total: 987654321.5,
            },
          ],
          totals: { orders: 99999999, total: 1e12 },
          truncated: false,
        }}
      />,
    );

    expect(widths()).toEqual(before);
    // What the width cuts is whole one hover away.
    expect(screen.getByText('OrderItemReservedTrackEventProcessor').title).toBe(
      'OrderItemReservedTrackEventProcessor',
    );
  });

  /**
   * Held per column, not per table: a column is sized from the answer it is
   * first drawn with, so the table opens fitting its rows, and a column the
   * question gains is sized from the answer it arrives with while the others
   * stay put.
   */
  it('sizes a column from the answer it first arrives with', () => {
    const name = 'OrderItemReservedTrackEventProcessor';
    const { rerender } = render(
      <AnalysisTable
        view={{
          columns,
          rows: [{ wh: name, orders: 2, total: 30 }],
          truncated: false,
        }}
      />,
    );
    const [group] = widths();
    const plain = columnWidthOf(columns[0]!, 'Warehouse', ['CN']);
    expect(parseFloat(group![0]!)).toBeGreaterThan(plain);

    rerender(
      <AnalysisTable
        view={{
          columns: [
            ...columns,
            { alias: 'avg', label: 'Amount', role: 'metric', fn: 'AVG' },
          ],
          rows: [{ wh: 'CN', orders: 2, total: 30, avg: 15 }],
          truncated: false,
        }}
      />,
    );

    const after = widths();
    expect(after[0]).toEqual(group);
    expect(after).toHaveLength(4);
    expect(after[3]![0]).toBe(
      `${columnWidthOf(
        { alias: 'avg', label: 'Amount', role: 'metric', fn: 'AVG' },
        'Average of Amount',
        ['15'],
      )}px`,
    );
  });
});

/**
 * The header sorts the groups when the table is handed a way to (the
 * workbench's `useHeaderSort`): the record table's own `SortableHeader`, so
 * the press, the arrow, `aria-sort` and the one Tab stop are that header's.
 * The cycle and the run are pinned in `test/analysisTableSort.test.tsx`.
 */
describe('an analysis header that sorts', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'wh', label: 'Warehouse', role: 'group' },
      { alias: 'p95', label: 'Latency', role: 'metric', fn: 'PERCENTILE' },
      { alias: 'orders', label: 'Orders', role: 'metric', fn: 'COUNT' },
    ],
    rows: [{ wh: 'CN', p95: 12, orders: 2 }],
    truncated: false,
  };

  it('presses through to the sort, and says the order it is in', () => {
    const onToggle = vi.fn();
    render(
      <AnalysisTable
        view={view}
        sorting={{ sort: [{ alias: 'orders', direction: 'DESC' }], onToggle }}
      />,
    );

    const headers = screen.getAllByRole('columnheader');
    expect(headers.map(cell => cell.getAttribute('aria-sort'))).toEqual([
      null,
      null,
      'descending',
    ]);
    fireEvent.click(within(headers[0]!).getByRole('button'));
    fireEvent.click(within(headers[2]!).getByRole('button'), {
      shiftKey: true,
    });

    expect(onToggle.mock.calls).toEqual([
      ['wh', { exclusive: true }],
      ['orders', { exclusive: false }],
    ]);
  });

  it('is one Tab stop, and the percentile still says it is approximate', () => {
    render(
      <AnalysisTable view={view} sorting={{ sort: [], onToggle: () => {} }} />,
    );

    const buttons = screen
      .getAllByRole('columnheader')
      .map(cell => within(cell).getByRole('button'));
    expect(buttons.map(button => button.tabIndex)).toEqual([0, -1, -1]);
    expect(describedText(buttons[1]!)).toBe(
      'Hold Shift to add to the sort Approximate',
    );
  });

  it('draws no control where nothing sorts', () => {
    render(<AnalysisTable view={view} />);

    expect(screen.queryAllByRole('button')).toEqual([]);
  });
});
