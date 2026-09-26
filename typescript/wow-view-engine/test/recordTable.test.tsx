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

import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { RecordSort, ViewInstance, ViewSource } from '../src/index.js';
import type { SummaryRow } from '../src/record/index.js';
import {
  defaultMessages,
  RecordTable,
  DataWorkbench,
  ViewSurface,
} from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  inZone,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { describedText, mine, twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

/** A `ResizeObserver` that reports what it was given and fires on demand. */
class ResizeSpy {
  readonly observed: Element[] = [];
  constructor(private readonly callback: ResizeObserverCallback) {}
  observe(node: Element): void {
    this.observed.push(node);
  }
  unobserve(): void {}
  disconnect(): void {}
  resize(): void {
    this.callback([], this as unknown as ResizeObserver);
  }
}

describe('RecordTable on its own', () => {
  it('hands a custom cell the null the record holds', () => {
    const seen: unknown[] = [];
    render(
      <RecordTable
        table={twoColumnTable()}
        renderCell={cell => {
          if (cell.key === 'o-2' && cell.column.field === 'amount')
            seen.push(cell.value);
          return null;
        }}
      />,
    );
    expect(seen).toEqual([null]);
  });

  it('formats each value by what the column declared', () => {
    render(<RecordTable table={twoColumnTable()} />);

    expect(screen.getByText('CN¥10.00')).toBeDefined();
    expect(screen.getByText('Yes')).toBeDefined();
    // A sortable column gets a button; a plain one is just its label.
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Warehouse' })).toBeNull();
  });

  /**
   * Wow keeps a time as epoch milliseconds and an enum as its code, so a
   * table that printed values as they came was a column of thirteen-digit
   * numbers beside a column of constants.
   */
  it('shows a time in the zone and language of its surface, and an enum by its label', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordTable
          table={twoColumnTable({
            columns: [
              {
                field: 'createdAt',
                label: 'Created',
                kind: 'datetime',
                cell: 'datetime',
                sortable: false,
              },
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'enum',
                sortable: false,
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
            ],
            rows: [
              { key: 'o-1', data: { createdAt: INSTANT, status: 'FAILED' } },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(inZone(INSTANT))).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
  });

  it('still renders a column whose number format Intl refuses', () => {
    render(
      <RecordTable
        table={twoColumnTable({
          columns: [
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              numberFormat: { style: 'currency' },
            },
          ],
          rows: [{ key: 'o-1', data: { amount: 10 } }],
        })}
      />,
    );

    expect(screen.getByText('10')).toBeDefined();
  });

  it('shows skeletons on a first load and an empty state after it', () => {
    render(
      <RecordTable table={twoColumnTable({ status: 'loading', rows: [] })} />,
    );
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);

    cleanup();
    render(<RecordTable table={twoColumnTable({ rows: [] })} />);
    expect(screen.getByText('Nothing to show')).toBeDefined();
  });

  /**
   * The empty result's one way out (D12 Ⅴ). Which of the two it is follows
   * from what was asked: conditions are why the rows are missing, so the
   * way out is to clear them; with none the view is already showing
   * everything there is, and the only thing left is to ask differently.
   */
  it('offers one way out of an empty result, and says which', async () => {
    const user = userEvent.setup();
    const onEmptyAction = vi.fn();
    render(
      <RecordTable
        table={twoColumnTable({ rows: [] })}
        emptyWayOut="clear"
        onEmptyAction={onEmptyAction}
      />,
    );

    const button = screen.getByRole('button', {
      name: defaultMessages['label.record.empty-clear'],
    });
    expect(
      screen.queryByRole('button', {
        name: defaultMessages['label.record.empty-add'],
      }),
    ).toBeNull();
    await user.click(button);
    expect(onEmptyAction).toHaveBeenCalledTimes(1);

    cleanup();
    render(
      <RecordTable
        table={twoColumnTable({ rows: [] })}
        onEmptyAction={onEmptyAction}
      />,
    );
    expect(
      screen.getByRole('button', {
        name: defaultMessages['label.record.empty-add'],
      }),
    ).toBeTruthy();
  });

  /**
   * A surface with no condition editor of its own — a dashboard panel, an
   * embedded view — has nowhere to send anybody, and a button that leads
   * nowhere is worse than no button.
   */
  it('offers no way out where the host gave it none', () => {
    render(<RecordTable table={twoColumnTable({ rows: [] })} />);

    expect(screen.getByText('Nothing to show')).toBeDefined();
    expect(screen.queryByRole('button')).toBeNull();
  });

  /**
   * The skeleton is drawn by column where the columns are known, each bar
   * as wide as the name above it: three equal bars said only "something is
   * loading", while unequal bars under the real headers say *this* table is
   * loading and what shape the answer will take.
   */
  it('draws the skeleton by column, each bar the width of its name', () => {
    const { container } = render(
      <RecordTable table={twoColumnTable({ status: 'loading', rows: [] })} />,
    );

    const row = container.querySelector('tbody tr')!;
    // The checkbox column, the two data columns and the filler that takes
    // the leftover width, rather than one cell
    // spanning all three.
    expect(row.querySelectorAll('td')).toHaveLength(4);
    const bars = [
      ...row.querySelectorAll<HTMLElement>('[data-slot="skeleton"]'),
    ];
    // "Amount" is six characters and "Warehouse" nine, and the two bars
    // are that wide — unequal, and in the proportions of the header above.
    expect(bars.slice(1).map(bar => bar.style.width)).toEqual(['6ch', '9ch']);
  });

  it('takes a renderer for the cells', () => {
    render(
      <RecordTable
        table={twoColumnTable()}
        renderCell={cell => <em>{String(cell.value)}</em>}
      />,
    );
    expect(screen.getByText('10')).toBeDefined();
  });
});

/**
 * Sorting from the headers.
 *
 * The controller applies a sort at once, so what a header has to do is make
 * the state legible: which columns order the table, in which direction, and —
 * once more than one does — in which order they are consulted.
 */
describe('sorting from the headers', () => {
  const sorted = (sort: RecordSort[]) =>
    twoColumnTable({
      sort,
      columns: [
        {
          field: 'id',
          label: 'Order',
          kind: 'string',
          cell: 'string',
          sortable: true,
        },
        {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          cell: 'number',
          sortable: true,
        },
        {
          field: 'status',
          label: 'Status',
          kind: 'string',
          cell: 'string',
          sortable: true,
        },
        {
          field: 'warehouse',
          label: 'Warehouse',
          kind: 'string',
          cell: 'string',
          sortable: false,
        },
      ],
    });

  /**
   * A column's name is the one thing in a header cell with a width of its
   * own, so it is the one that truncates — and «订..» is a column with no
   * name at all. The whole of it is one hover away, as a `Tooltip` and not
   * the native `title` (D16-6): `title` opens for a mouse and for nothing
   * else, while this header is a button that a keyboard reaches.
   */
  it('carries the whole column name it may have to truncate', () => {
    const { container } = render(<RecordTable table={sorted([])} />);

    // The sortable one and the plain one both answer: the trigger is the
    // name itself, not the button that happens to be around one of them.
    for (const field of ['id', 'warehouse']) {
      const name = header(container, field).querySelector<HTMLElement>(
        '[data-slot="column-label"]',
      )!;
      // The name is the trigger, which is what makes the clipping bearable
      // — a `Tooltip` and not the native `title`, which opens for a mouse
      // and for nothing else (D16-6). That the name really is clipped is
      // the browser story's to measure: jsdom lays out no text.
      expect(name.hasAttribute('data-base-ui-tooltip-trigger')).toBe(true);
      expect(name.textContent).toBe(field === 'id' ? 'Order' : 'Warehouse');
      // Not two ways of saying the same thing, and not the one only a mouse
      // can open.
      expect(name.getAttribute('title')).toBeNull();
    }
  });

  it('offers a neutral mark on a sortable column nobody has sorted', () => {
    const { container } = render(<RecordTable table={sorted([])} />);

    const head = header(container, 'amount');
    expect(head.querySelector('[data-slot="sort-available"]')).not.toBeNull();
    // A bare button shows focus the way the vendored button does, not with
    // the UA outline — which is to say it *is* the vendored button rather
    // than a `<button>` with classes on it.
    expect(head.querySelector<HTMLElement>('button')!.dataset.slot).toBe(
      'button',
    );
    // Nothing is sorted, so nothing claims to be: a row of headers each
    // announcing `none` is noise, not information.
    expect(container.querySelectorAll('thead [aria-sort]')).toHaveLength(0);
    // A column that cannot be sorted offers nothing at all, not even a mark.
    const plain = header(container, 'warehouse');
    expect(plain.hasAttribute('aria-sort')).toBe(false);
    expect(plain.querySelector('button')).toBeNull();
  });

  /**
   * One mark per header, on the inner side of the column's name.
   *
   * The affordance and the direction are the same sentence, so a sorted
   * header does not keep the neutral `↕` beside its arrow — the bar's own
   * sort button read `↕ Amount ↓` for exactly that reason. Inner side means
   * *after* the name where a column reads from the left and *before* it
   * where a numeric column reads from the right, which is one rule and not
   * two: the label keeps the edge the digits under it line up on, and the
   * mark follows it inward.
   */
  it('marks a header once, on the inner side of its name', () => {
    const { container } = render(
      <RecordTable table={sorted([{ field: 'amount', direction: 'DESC' }])} />,
    );

    const marks = (field: string) => [
      ...header(container, field).querySelectorAll('svg'),
    ];
    // The resizer draws no glyph, so every svg in a header is a sort mark.
    expect(marks('amount')).toHaveLength(1);
    expect(marks('id')).toHaveLength(1);
    expect(marks('warehouse')).toHaveLength(0);
    expect(marks('amount')[0].dataset.slot).toBe('sort-direction');
    expect(marks('id')[0].dataset.slot).toBe('sort-available');

    // A left-reading column puts its name first and the mark after it; a
    // numeric one is right-aligned and reverses that row, which puts the
    // same mark between the name and the rest of the table.
    const order = (field: string) =>
      [...header(container, field).querySelector('button')!.children].map(
        child => (child as HTMLElement).dataset.slot,
      );
    expect(order('id')).toEqual(['column-label', 'sort-available']);
    expect(order('amount')).toEqual(['column-label', 'sort-direction']);
    // A **surviving class assertion**. The inward step is a *visual* order
    // with no DOM counterpart, deliberately: a reader hears the column's
    // name first either way, and `order(…)` above is identical for the two
    // columns for exactly that reason. So the reversal has nothing to be
    // said on — the class is the whole of it.
    expect(
      header(container, 'amount').querySelector('button')!.className,
    ).toContain('flex-row-reverse');
    expect(
      header(container, 'id').querySelector('button')!.className,
    ).not.toContain('flex-row-reverse');
  });

  it('says which way one sorted column goes, and what a click would do next', () => {
    const { container } = render(
      <RecordTable table={sorted([{ field: 'amount', direction: 'ASC' }])} />,
    );

    const head = header(container, 'amount');
    expect(head.getAttribute('aria-sort')).toBe('ascending');
    // Ascending, then descending, then off: the name is the next step.
    expect(head.querySelector('button')!.getAttribute('aria-label')).toBe(
      'Sort by Amount, descending',
    );
    // One sorted column has no position worth showing.
    expect(head.querySelector('[data-slot="sort-position"]')).toBeNull();
  });

  it('numbers each header while three columns order the table', () => {
    const { container } = render(
      <RecordTable
        table={sorted([
          { field: 'status', direction: 'ASC' },
          { field: 'amount', direction: 'DESC' },
          { field: 'id', direction: 'ASC' },
        ])}
      />,
    );

    expect(position(container, 'status')).toBe('1');
    expect(position(container, 'amount')).toBe('2');
    expect(position(container, 'id')).toBe('3');
    // ARIA marks the column the table is ordered by, and there is one of
    // those however many columns break its ties; the rest would otherwise
    // announce two columns as sorted with nothing saying which comes first.
    expect(
      [...container.querySelectorAll('thead [aria-sort]')].map(cell => [
        (cell as HTMLElement).dataset.field,
        cell.getAttribute('aria-sort'),
      ]),
    ).toEqual([['status', 'ascending']]);
    // The place in the order is read as well as seen.
    expect(
      header(container, 'amount')
        .querySelector('button')!
        .getAttribute('aria-label'),
    ).toContain('sort 2 of 3');
  });

  it('cycles a column off through the pointer and the keyboard alike', async () => {
    const toggled: string[] = [];
    const table = sorted([{ field: 'amount', direction: 'DESC' }]);
    const { container } = render(
      <RecordTable table={{ ...table, toggleSort: f => toggled.push(f) }} />,
    );

    const button = header(container, 'amount').querySelector('button')!;
    expect(button.getAttribute('aria-label')).toBe('Stop sorting by Amount');
    await userEvent.click(button);
    // Every sort is one activation of a focusable button, so the keyboard
    // reaches all of it: there is no modifier to hold and none to emulate.
    button.focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard(' ');
    expect(toggled).toEqual(['amount', 'amount', 'amount']);
  });

  /**
   * A plain click orders the rows by this column alone; a modifier adds the
   * column to the sort. The keyboard walks the same handler, because a
   * button's activation click carries the modifiers held with it.
   */
  it('sorts by the column alone on a plain click, and adds it with Shift', async () => {
    const calls: Array<[string, boolean | undefined]> = [];
    const table = sorted([{ field: 'status', direction: 'ASC' }]);
    const { container } = render(
      <RecordTable
        table={{
          ...table,
          toggleSort: (f, options) => calls.push([f, options?.exclusive]),
        }}
      />,
    );

    const button = header(container, 'amount').querySelector('button')!;
    // The way to add is said on the button, since nothing else shows it.
    expect(describedText(button)).toBe('Hold Shift to add to the sort');

    // One user, so the Shift held on the keyboard is on the pointer too.
    const user = userEvent.setup();
    await user.click(button);
    await user.keyboard('{Shift>}');
    await user.click(button);
    await user.keyboard('{/Shift}');

    button.focus();
    await user.keyboard('{Enter}');
    await user.keyboard('{Shift>}{Enter}{/Shift}');
    await user.keyboard('{Shift>} {/Shift}');

    expect(calls).toEqual([
      ['amount', true],
      ['amount', false],
      ['amount', true],
      ['amount', false],
      ['amount', false],
    ]);
  });
});

/** A status is one of a known set, and reads as one. */
describe('enum cells', () => {
  const status = (
    options: { value: string; label: string }[] | undefined,
    value: unknown = 'PENDING',
  ) =>
    twoColumnTable({
      columns: [
        {
          field: 'status',
          label: 'Status',
          kind: 'enum',
          cell: 'enum',
          sortable: false,
          ...(options ? { options } : {}),
        },
      ],
      rows: [{ key: 'o-1', data: { status: value } }],
    });

  const PENDING = [{ value: 'PENDING', label: 'Pending' }];

  it('wears the option label as a badge', () => {
    const { container } = render(<RecordTable table={status(PENDING)} />);

    const badge = container.querySelector('[data-slot="badge"]')!;
    expect(badge.textContent).toBe('Pending');
    // Neutral until a definition can say otherwise; nothing here guesses
    // which of a definition's own statuses is good news.
    expect(badge.getAttribute('data-variant')).toBe('secondary');
  });

  it('gives an array of values one badge each', () => {
    const { container } = render(
      <RecordTable
        table={status(
          [...PENDING, { value: 'SHIPPED', label: 'Shipped' }],
          ['PENDING', 'SHIPPED'],
        )}
      />,
    );

    expect(
      [...container.querySelectorAll('[data-slot="badge"]')].map(
        node => node.textContent,
      ),
    ).toEqual(['Pending', 'Shipped']);
  });

  /**
   * A label is not an identity: a list may hold the same value twice and two
   * options may be worded alike, so badges keyed by their text would collide
   * — and two children under one key is a reconciliation React is free to get
   * wrong, and warns about.
   */
  it('keeps repeated values and repeated wording apart', () => {
    const complained = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = render(
      <RecordTable
        table={status(
          [
            { value: 'PENDING', label: 'Open' },
            // Two codes the definition words the same way.
            { value: 'HELD', label: 'Open' },
          ],
          ['PENDING', 'HELD', 'PENDING'],
        )}
      />,
    );

    expect(
      [...container.querySelectorAll('[data-slot="badge"]')].map(
        node => node.textContent,
      ),
    ).toEqual(['Open', 'Open', 'Open']);
    expect(complained).not.toHaveBeenCalled();
  });

  it('leaves a code nobody named as plain text', () => {
    const { container } = render(
      <RecordTable table={status(undefined, 'PENDING')} />,
    );

    expect(container.querySelector('[data-slot="badge"]')).toBeNull();
    expect(screen.getByText('PENDING')).toBeDefined();
  });
});

/**
 * The chrome around the rows: what stays put while the rest moves. jsdom
 * computes no layout, so what is asserted is the contract the browser then
 * honours — a sticky position and the offset it sticks at.
 */
describe('the table chrome', () => {
  const pinned = () =>
    twoColumnTable({
      columns: [
        {
          field: 'id',
          label: 'Order',
          kind: 'string',
          cell: 'string',
          sortable: false,
          pinned: 'left',
          width: 120,
        },
        {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          cell: 'number',
          sortable: false,
        },
        {
          field: 'status',
          label: 'Status',
          kind: 'string',
          cell: 'string',
          sortable: false,
          pinned: 'right',
        },
      ],
      rows: [{ key: 'o-1', data: { id: 'o-1', amount: 10, status: 'CN' } }],
    });

  /** A count under `Amount`: a band is drawn only under a summarised column. */
  const AMOUNT_COUNTED: SummaryRow = {
    scope: 'page',
    cells: [{ field: 'amount', label: 'Amount', fn: 'COUNT', value: 2 }],
  };

  it('sticks the header over the rows and the summaries under them', () => {
    const { container } = render(
      <RecordTable
        table={twoColumnTable({
          summaries: AMOUNT_COUNTED,
        })}
      />,
    );

    // One scroll area, so the sideways scrollbar sits under the summaries
    // rather than between them and the rows. The shape is said on the
    // element: the expanded workbench hands the remaining height to the
    // table that is its own scrollport, and it has to be able to tell which
    // one that is.
    const area = container.querySelector('[data-slot="record-table"]')!;
    expect(area.hasAttribute('data-scrolls')).toBe(true);
    // And each band says which end of that port it holds. jsdom lays
    // nothing out, so the truth is the browser's — `PinnedEdges` reads the
    // computed `position` of all three layers, scrolled and at rest.
    expect(container.querySelector('thead')!.dataset.sticky).toBe('top');
    expect(container.querySelector('tfoot')!.dataset.sticky).toBe('bottom');
  });

  /**
   * A box that scrolls sideways is a scrollport both ways — CSS has no
   * one-axis overflow — so inside a surface that scrolls itself the wrapper
   * must not be one at all: a dashboard panel shorter than this table's own
   * height would otherwise move the header off the top while it stayed put
   * against a box nothing ever scrolls.
   */
  it('leaves the scrolling to the surface around it when asked', () => {
    const { container } = render(
      <RecordTable
        scrolls={false}
        table={twoColumnTable({ summaries: AMOUNT_COUNTED })}
      />,
    );

    const area = container.querySelector('[data-slot="record-table"]')!;
    // It says which shape it is, so that an expanded workbench around it
    // does not give it a height and put a second scrollport back under the
    // sticky layers.
    expect(area.hasAttribute('data-scrolls')).toBe(false);
    // A **surviving class assertion**. The registry's own container stays
    // out of the way in either shape,
    // and that is a declaration aimed at a *descendant* — there is no
    // element of this component's to say it on, and two nested scrollports
    // is precisely the defect: the sticky layers would resolve against the
    // inner one, which nothing ever scrolls.
    expect(area.className).toContain(
      '[&>[data-slot=table-container]]:overflow-visible',
    );
    // The header and summaries still hold — against whatever really
    // scrolls, which the dashboard story measures for real.
    expect(container.querySelector('thead')!.dataset.sticky).toBe('top');
    expect(container.querySelector('tfoot')!.dataset.sticky).toBe('bottom');
  });

  it('pins a column on each side and leaves the middle to scroll', () => {
    // No row actions: with one, the host's slot is the whole of the right
    // side and the table's own last column lets go (D19).
    const { container } = render(<RecordTable table={pinned()} />);

    // Every cell of a pinned column, header and body alike: a header that
    // stays while its cells leave is worse than no pinning at all.
    for (const cell of cellsOf(container, 'id')) {
      expect(cell.dataset.pin).toBe('left');
      // It clears the selection column rather than sitting on it, at the
      // measured offset where there is one and the config's own until then.
      expect(cell.style.left).toBe('var(--_fve-pin-left-0, calc(2.5rem))');
    }
    for (const cell of cellsOf(container, 'status')) {
      expect(cell.dataset.pin).toBe('right');
      // Held against the edge itself: it is the one column the right side
      // has (D19), so there is never anything out there for it to clear —
      // no offset to measure, and a flat `right-0` rather than a variable
      // that would resolve to zero every time (A9).
      expect(cell.style.right).toBe('');
      expect(cell.className).toContain('right-0');
    }
    // The selection column is pinned along with them, or the pinned column
    // would scroll over the checkboxes.
    expect(container.querySelector<HTMLElement>('thead th')!.dataset.pin).toBe(
      'left',
    );
    // The middle column stays where it is.
    expect(cellsOf(container, 'amount')[0].dataset.pin).toBeUndefined();
  });

  /**
   * The second pinned column clears the first from its declared width. One
   * per side needs no width at all, which is the case pinning is for; a
   * stack of them is the case a config has to measure for itself.
   */
  it('stacks two pinned columns on their declared widths', () => {
    const { container } = render(
      <RecordTable
        selectable={false}
        table={twoColumnTable({
          columns: [
            {
              field: 'id',
              label: 'Order',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
              width: 120,
            },
            {
              field: 'status',
              label: 'Status',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
            },
          ],
          rows: [{ key: 'o-1', data: { id: 'o-1', status: 'CN' } }],
        })}
      />,
    );

    // Nothing to the left of the first; the second starts where it ends.
    expect(cellsOf(container, 'id')[0].style.left).toBe(
      'var(--_fve-pin-left-0, 0px)',
    );
    expect(cellsOf(container, 'status')[0].style.left).toBe(
      'var(--_fve-pin-left-1, calc(120px))',
    );
  });

  /**
   * What the class asks for is not what the browser gives: a table lays out
   * by content, so the selection column is as wide as the widest thing in it
   * — the scope label — and a column pinned beside it by the class's own
   * `2.5rem` lands on top of the checkboxes. The header is measured after
   * every layout and the offsets go onto the table as it finds them; the
   * config's arithmetic is only the fallback until then.
   */
  it('clears the columns before it by what the header actually measures', () => {
    const rendered: Record<string, number> = {
      select: 77,
      id: 100,
      actions: 48,
    };
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
      function (this: Element) {
        const cell = this as HTMLElement;
        const key = cell.dataset.column ?? cell.dataset.field ?? '';
        return { width: rendered[key] ?? 0 } as DOMRect;
      },
    );

    const { container } = render(
      <RecordTable
        table={twoColumnTable({
          columns: [
            {
              field: 'id',
              label: 'Order',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
              // Declared narrower than it renders: the measurement wins.
              width: 60,
            },
            {
              field: 'status',
              label: 'Status',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
            },
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              pinned: 'right',
            },
          ],
          rows: [{ key: 'o-1', data: { id: 'o-1', status: 'CN', amount: 1 } }],
        })}
        rowActions={() => <button />}
      />,
    );

    const table = container.querySelector('table')!;
    // The first pinned column clears the selection column as rendered, not
    // as `w-10` asks; the second clears both.
    expect(table.style.getPropertyValue('--_fve-pin-left-0')).toBe('77px');
    expect(table.style.getPropertyValue('--_fve-pin-left-1')).toBe('177px');
    // And nothing is published for the right, because nothing is held
    // there: the host's action column is that whole side (D19), so the
    // table's own last column let go before any offset was added up.
    expect(table.style.getPropertyValue('--fve-pin-right-2')).toBe('');
    expect(cellsOf(container, 'amount')[0].dataset.pin).toBeUndefined();
    // Every cell of the column reads the same offset, header to footer.
    for (const cell of cellsOf(container, 'status'))
      expect(cell.style.left).toBe(
        'var(--_fve-pin-left-1, calc(2.5rem + 60px))',
      );
  });

  /**
   * A column can change width while the table's own box does not — a web
   * font finishing, a row-action button growing — and offsets published from
   * the last layout would then hold the pinned columns over their
   * neighbours. What is watched is therefore the cells the offsets are added
   * up from, not the table.
   */
  it('follows a header cell that resizes while the table does not', () => {
    const rendered: Record<string, number> = { select: 77, id: 100 };
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
      function (this: Element) {
        const cell = this as HTMLElement;
        const key = cell.dataset.column ?? cell.dataset.field ?? '';
        return { width: rendered[key] ?? 0 } as DOMRect;
      },
    );
    const observers: ResizeSpy[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class extends ResizeSpy {
        constructor(callback: ResizeObserverCallback) {
          super(callback);
          observers.push(this);
        }
      },
    );

    const { container } = render(<RecordTable table={pinned()} />);
    const table = container.querySelector('table')!;
    expect(table.style.getPropertyValue('--_fve-pin-left-0')).toBe('77px');

    // The header cells that feed the offsets, and nothing else: the table
    // itself can sit still through all of this. (The edges and the pin cap
    // keep observers of their own on the scrollport, and the cap watches
    // header cells beside it; the offsets' is the one watching header cells
    // and nothing else.)
    const latest = observers.find(
      spy =>
        spy.observed.length > 0 &&
        spy.observed.every((node: Element) => node.tagName === 'TH'),
    )!;
    const watching: Element[] = latest.observed;
    expect(watching.every((node: Element) => node.tagName === 'TH')).toBe(true);
    expect(watching).toContain(
      container.querySelector('thead th[data-column="select"]'),
    );
    expect(watching).not.toContain(table);

    // The scope label makes the selection column wider without the table
    // moving; the pinned column follows it rather than sitting on it.
    rendered.select = 120;
    act(() => latest.resize());
    expect(table.style.getPropertyValue('--_fve-pin-left-0')).toBe('120px');
    vi.unstubAllGlobals();
  });

  it('pins nothing but the actions when no column asked for it', () => {
    const { container } = render(<RecordTable table={twoColumnTable()} />);
    expect(
      container.querySelector<HTMLElement>('thead th')!.dataset.pin,
    ).toBeUndefined();
  });
});

/**
 * What a table draws when it has no result to draw.
 *
 * The columns come from the result, so a view that never got one has none:
 * what used to be drawn was a header of a single empty cell over no rows,
 * carrying a tab-reachable "Select all rows" that named rows there were none
 * of and changed nothing when pressed. Two states have something to show and
 * keep their table — the first query's skeleton, and rows a failed refresh
 * could not replace — and everything else has nothing.
 */
describe('a record view with no result', () => {
  function workbench(
    instance: ViewInstance,
    source: ViewSource = testSource(),
  ) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [instance] }),
      resolveSource: () => source,
    });
    return render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={instance.id}
      />,
    );
  }

  /** The select-all, which is the control the dead table kept offering. */
  const selectAll = () =>
    screen.queryByRole('checkbox', { name: 'Select all rows' });

  it('draws nothing at all when the first query failed', async () => {
    workbench(
      mine,
      testSource({
        paged: vi.fn(() => Promise.reject(new Error('gateway down'))),
      }),
    );

    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain('gateway down');
    // No frame, and above all no control: the strip already says what
    // happened and offers the retry. A header over nothing says nothing.
    expect(screen.queryByRole('table')).toBeNull();
    expect(selectAll()).toBeNull();
    // And not the other sentence either — this query did not return nothing,
    // it did not return.
    expect(screen.queryByText('Nothing to show')).toBeNull();
  });

  it('draws nothing for a config the definition refuses to run', async () => {
    workbench({
      ...mine,
      config: recordConfig({
        table: { columns: [{ field: 'removedColumn' }] },
      }),
    });

    // `apply` was refused, so the query never ran and never will until the
    // config is fixed: status stays idle, and there is nothing on the way.
    // The one finding, said outright, with the way out at the end of it —
    // and no result block at all around a toolbar with nothing under it.
    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain(
      'The column removedColumn no longer exists.',
    );
    expect(
      within(strip).getByRole('button', { name: 'Open column settings' }),
    ).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    expect(selectAll()).toBeNull();
    expect(document.querySelector('[data-slot="result-block"]')).toBeNull();
  });

  it('opens the column settings from the strip, where the toolbar is not', async () => {
    const user = userEvent.setup();
    workbench({
      ...mine,
      config: recordConfig({
        table: { columns: [{ field: 'removedColumn' }] },
      }),
    });

    const strip = await screen.findByRole('alert');
    await user.click(
      within(strip).getByRole('button', { name: 'Open column settings' }),
    );

    // The panel the toolbar's button opens, opened from the one line on
    // screen that says why there is no toolbar.
    expect(await screen.findByText('Column settings')).toBeTruthy();
  });

  it('keeps the skeleton while the first query is still running', async () => {
    // A query that never settles: the view stays on its first `loading`.
    workbench(
      mine,
      testSource({ paged: vi.fn((): Promise<never> => new Promise(() => {})) }),
    );

    // A query in flight has something to show, and the skeleton is it.
    const table = await screen.findByRole('table');
    expect(table.querySelectorAll('tbody tr')).toHaveLength(3);
    // But nothing to head it with, and above all no control: the columns come
    // from a result there has not been one of, so the header this used to draw
    // was the dead table's — one cell carrying a select-all over rows that do
    // not exist yet.
    expect(table.querySelector('thead')).toBeNull();
    expect(selectAll()).toBeNull();
    // Nor the bar under it, which would have counted those same rows.
    expect(screen.queryByText('0 on this page')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull();
  });

  /**
   * The header comes back with the result, and it comes back whole — a
   * refresh over rows already on screen never loses it.
   */
  it('keeps the header once a result has been counted', async () => {
    workbench(mine);

    expect(await screen.findByRole('columnheader', { name: /Amount/ }));
    expect(selectAll()).not.toBeNull();
  });

  it('says in its own words that a query matched nothing', async () => {
    workbench(
      mine,
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
      }),
    );

    // The conditions ran and these are the records there are — a different
    // sentence from a failure, because a user acts differently on each. It
    // is drawn here and said out loud by the result's live region, so the
    // one on screen is addressed by the part that draws it.
    await screen.findByText('Nothing to show', {
      selector: '[data-slot="empty-title"]',
    });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(selectAll()).toBeNull();
  });
});

function header(container: HTMLElement, field: string): HTMLElement {
  const found = container.querySelector<HTMLElement>(
    `thead [data-field="${field}"]`,
  );
  if (!found) throw new Error(`no header for ${field}`);
  return found;
}

function position(container: HTMLElement, field: string): string | undefined {
  return header(container, field).querySelector('[data-slot="sort-position"]')
    ?.textContent as string | undefined;
}

/** Every cell of one column, in the header and in the body. */
function cellsOf(container: HTMLElement, field: string): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-field') === field,
  );
  return [...container.querySelectorAll('thead tr, tbody tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}
