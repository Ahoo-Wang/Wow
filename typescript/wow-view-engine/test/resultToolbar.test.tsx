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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FieldDefinition } from '../src/model/index.js';
import type {
  RecordBulkActionContext,
  RecordTableController,
} from '../src/react/index.js';
import type { RecordViewRuntime } from '../src/runtime/index.js';
import { ResultToolbar } from '../src/ui/ResultToolbar.js';

afterEach(cleanup);

/**
 * The toolbar reads nothing off the runtime — it only hands it to a bulk
 * action — so a sentinel is enough to prove it hands over the same one.
 */
const runtime = { id: 'r-1' } as unknown as RecordViewRuntime;

const FIELDS: FieldDefinition[] = [
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
];

function tableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return {
    columns: [
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        sortable: false,
      },
    ],
    card: { title: 'amount', fields: [] },
    rows: [
      { key: 'o-1', data: { amount: 1 } },
      { key: 'o-2', data: { amount: 2 } },
      { key: 'o-3', data: { amount: 3 } },
    ],
    paging: { mode: 'paged', index: 1, total: 3 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    setSort: () => {},
    maxSortFields: 8,
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount'],
    setColumns: () => {},
    setColumnOrder: () => {},
    pinnedOf: () => null,
    setPinned: () => {},
    summaryOf: () => null,
    setSummary: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: false,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

describe('ResultToolbar selection side', () => {
  it('shows nothing about a selection there is none of, and still holds its height', () => {
    const { container } = render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    expect(screen.queryByRole('status')).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'Clear selection' }),
    ).toBeNull();
    // The row keeps a button's height so picking the first row does not
    // shove the result down a line.
    expect(
      container.querySelector('[data-slot="result-toolbar"] .min-h-8'),
    ).toBeTruthy();
  });

  /**
   * A bulk action names what it is about to touch, so it is handed the rows
   * and not only their keys — in the order the result is in, which is the
   * order the user is reading, rather than the order they were clicked.
   */
  it('hands a bulk action the selected rows in result order', () => {
    const seen: RecordBulkActionContext[] = [];
    const table = tableController({
      // Clicked bottom-up; the controller hands them back top-down.
      selection: ['o-3', 'o-1'],
      selectedRows: [
        { key: 'o-1', data: { amount: 1 } },
        { key: 'o-3', data: { amount: 3 } },
      ],
    });

    render(
      <ResultToolbar
        table={table}
        fields={FIELDS}
        runtime={runtime}
        bulkActions={context => {
          seen.push(context);
          return <button type="button">{'Retry these'}</button>;
        }}
      />,
    );

    expect(screen.getByRole('status').textContent).toBe('2 selected');
    expect(screen.getByRole('button', { name: 'Retry these' })).toBeTruthy();
    expect(seen).toHaveLength(1);
    expect(seen[0].rows.map(row => row.key)).toEqual(['o-1', 'o-3']);
    expect(seen[0].keys).toEqual(['o-3', 'o-1']);
    expect(seen[0].runtime).toBe(runtime);
    expect(seen[0].clearSelection).toBe(table.clearSelection);
    expect(seen[0].refresh).toBe(table.refresh);
  });

  it('clears the selection from its own button', async () => {
    const clearSelection = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController({ selection: ['o-1'], clearSelection })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(clearSelection).toHaveBeenCalledTimes(1);
  });
});

describe('ResultToolbar layout switcher', () => {
  it('offers nothing when the definition allows one layout', () => {
    render(
      <ResultToolbar
        table={tableController({ layouts: ['table'] })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    expect(screen.queryByRole('group', { name: 'Layout' })).toBeNull();
    expect(screen.queryByRole('radio', { name: 'Table' })).toBeNull();
  });

  /**
   * Unless the view is saved in a layout the definition has since dropped.
   * `validateRecord` refuses that config, and a switcher that hides itself
   * exactly then leaves the user reading an error with no way to answer it.
   */
  it('stays when the layout in force is one the definition dropped', () => {
    render(
      <ResultToolbar
        table={tableController({ layouts: ['table'], layout: 'card' })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    const group = screen.getByLabelText('Layout');
    expect(
      [...group.querySelectorAll('button')].map(item => item.textContent),
    ).toEqual(['Table']);
    // Nothing is pressed, which is the truth: the layout in force is not one
    // of these.
    expect(group.querySelector('[aria-pressed="true"]')).toBeNull();
  });

  /** Only what the definition allows, in the order the definition wrote. */
  it('offers the allowed layouts in the definition order', () => {
    render(
      <ResultToolbar
        table={tableController({ layouts: ['card', 'table'], layout: 'card' })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    const group = screen.getByLabelText('Layout');
    expect(
      [...group.querySelectorAll('button')].map(item => item.textContent),
    ).toEqual(['Cards', 'Table']);
  });

  it('applies the layout that was picked', async () => {
    const setLayout = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController({ setLayout })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    await user.click(screen.getByText('Cards'));
    expect(setLayout).toHaveBeenCalledWith('card');
  });
});

/**
 * The right of the row is three groups by responsibility: the layout switch,
 * then how the table shows what it has, then how fresh it is. The grouping
 * is the point — four equal buttons in a row say nothing about which of them
 * belong together — so it is asserted rather than left to a screenshot.
 */
describe('ResultToolbar grouping and weight', () => {
  it('groups the controls on the right by what they are for', () => {
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    expect(
      screen
        .getAllByRole('group')
        .map(group => group.getAttribute('aria-label')),
    ).toEqual(['Layout', 'Table settings', 'Freshness']);
  });

  /**
   * The toolbar sits above the result and must not compete with it. The one
   * exception is the layout switch, whose single outline is what makes it
   * read as one control with two positions rather than as two buttons.
   */
  it('keeps every button ghost but the layout switch', () => {
    const { container } = render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    const toolbar = container.querySelector('[data-slot="result-toolbar"]')!;
    const outlined = [
      ...toolbar.querySelectorAll('[data-slot="button"]'),
    ].filter(button => button.className.includes('border-border'));
    expect(outlined).toEqual([]);
    // One border around the pair, no seam between them. The house rule has
    // one spelling of that — `SEGMENTED` in `ui/layout.ts` — applied at the
    // call site, because `ui/components` is upstream's.
    expect(
      toolbar.querySelector('[data-slot="toggle-group"]')!.className,
    ).toContain('[&>*+*]:-ml-px');
  });
});

describe('ResultToolbar columns and refresh', () => {
  it('adds a field the column settings turn on', async () => {
    const setColumns = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController({ setColumns })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    expect(setColumns).toHaveBeenCalledWith(['amount', 'warehouse']);
  });

  /**
   * Nothing in this definition can be sorted on, so there is no sort to
   * offer and no button to open an empty editor with.
   */
  it('leaves the sort out when the definition sorts on nothing', () => {
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    expect(screen.queryByRole('button', { name: /Sort/ })).toBeNull();
  });

  it('refreshes, and says so while the query is out', async () => {
    const refresh = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(
      <ResultToolbar
        table={tableController({ refresh })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Refresh/ }));
    expect(refresh).toHaveBeenCalledTimes(1);

    rerender(
      <ResultToolbar
        table={tableController({ refresh, loading: true })}
        fields={FIELDS}
        runtime={runtime}
      />,
    );
    expect(
      screen.getByRole('button', { name: /Refresh/ }).hasAttribute('disabled'),
    ).toBe(true);
  });
});
