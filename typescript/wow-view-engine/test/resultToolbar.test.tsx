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

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FieldDefinition } from '../src/model/index.js';
import type {
  RecordBulkActionContext,
  RecordExportController,
  RecordTableController,
} from '../src/react/index.js';
import type { RecordViewRuntime } from '../src/runtime/index.js';
import type { ExportOffer } from '../src/ui/ExportDialog.js';
import { ResultToolbar } from '../src/ui/ResultToolbar.js';

afterEach(cleanup);

/**
 * The toolbar reads one thing off the runtime — the export ceiling the
 * window puts on screen — and otherwise only hands it to a bulk action, so a
 * sentinel is enough to prove it hands over the same one.
 */
const runtime = {
  id: 'r-1',
  limits: { exportMax: 10000 },
} as unknown as RecordViewRuntime;

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
    hasResult: true,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    setSort: () => {},
    maxSortFields: 8,
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount'],
    hiddenOf: () => false,
    setColumns: () => {},
    setColumnOrder: () => {},
    pinnedOf: () => null,
    setPinned: () => {},
    setColumnWidth: () => {},
    summaryOf: () => null,
    summaryFields: [],
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

/**
 * The export as the toolbar sees it: counts, one `run` and what came of it.
 * The controller is `useRecordExport`'s, and it is stubbed here so the window
 * is tested for what it draws rather than for what a fetch does — the hook
 * has its own suite.
 */
function exportController(
  overrides: Partial<RecordExportController> = {},
): RecordExportController {
  return {
    scopes: { all: 42 },
    running: null,
    progress: null,
    outcome: null,
    error: null,
    run: () => {},
    cancel: () => {},
    reset: () => {},
    ...overrides,
  };
}

/** The offer as a workbench makes it: the run, the conditions, the name. */
function exportOffer(
  overrides: Partial<RecordExportController> = {},
): ExportOffer {
  return {
    control: exportController(overrides),
    conditions: [],
    fileName: 'Mine-2026-09-21.csv',
  };
}

describe('ResultToolbar selection side', () => {
  it('shows nothing about a selection there is none of, and no box where it would go', () => {
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
    // Not even an empty box: the placeholder that used to hold a button's
    // height was 32px of nothing that a narrow bar could give a line of its
    // own to, and the groups on the right are buttons that hold that same
    // height whether or not anything is selected.
    expect(
      container.querySelector('[data-slot="toolbar-selection"]'),
    ).toBeNull();
  });

  /**
   * The three groups on the right are one block, not three siblings of a
   * spacer: a spacer takes a line of its own width when the bar wraps, which
   * is what used to strand the layout switch alone on the first line and
   * push the refresh button down to a third.
   */
  it('keeps the three right-hand groups in one block that ends the bar', () => {
    const { container } = render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    const right = container.querySelector<HTMLElement>(
      '[data-slot="toolbar-arrangement"]',
    )!;
    expect(right.className).toContain('ml-auto');
    expect(right.className).toContain('flex-wrap');
    expect(right.className).toContain('justify-end');
    expect(
      [...right.children].map(node => node.getAttribute('aria-label')),
    ).toEqual(['Layout', 'Table settings']);
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
      [...group.querySelectorAll('button')].map(item =>
        item.getAttribute('aria-label'),
      ),
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
    // Icons with the word in their name (D12): the switch reports its state
    // by which segment is pressed.
    expect(
      [...group.querySelectorAll('button')].map(item =>
        item.getAttribute('aria-label'),
      ),
    ).toEqual(['Cards', 'Table']);
    expect(group.querySelector('button svg')).not.toBeNull();
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

    await user.click(screen.getByRole('button', { name: 'Cards' }));
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
    ).toEqual(['Layout', 'Table settings']);
  });

  /**
   * Every function on the bar is a bordered icon button (D12 Ⅳ): a bare
   * word read as a label rather than as a control. Only the sort keeps its
   * words, because what it says is the sort in force.
   */
  it('draws every function as a bordered button, icons where nothing is reported', () => {
    const { container } = render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    const right = container.querySelector('[data-slot="toolbar-arrangement"]')!;
    // The layout pair is one bordered control of its own; every other
    // function is a bordered button.
    const buttons = [...right.querySelectorAll('button')].filter(
      button => !button.closest('[data-slot="toggle-group"]'),
    );
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons)
      expect(button.className).toContain('border-border');
    // Columns reports nothing, so it is an icon with the word in its name.
    const columns = screen.getByRole('button', { name: 'Columns' });
    expect(columns.textContent).toBe('');
    expect(columns.querySelector('svg')).not.toBeNull();
    // One border around the layout pair, no seam between them: the
    // registry's own joined group, asked for by `spacing={0}` rather than
    // hand-written at the call site.
    const group = right.querySelector<HTMLElement>(
      '[data-slot="toggle-group"]',
    )!;
    expect(group.dataset.spacing).toBe('0');
    for (const item of group.querySelectorAll(
      '[data-slot="toggle-group-item"]',
    ))
      expect(item.getAttribute('data-spacing')).toBe('0');
  });
});

describe('ResultToolbar hint', () => {
  /**
   * The left of the bar is where the bulk actions will appear; before a row
   * is picked it says so — and says nothing when the host brought no bulk
   * action, since there would be nothing to reach.
   */
  it('says how the bulk actions are reached, only when there are any', () => {
    const { container, rerender } = render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        bulkActions={() => <button>Export</button>}
      />,
    );
    expect(screen.getByText('Select rows to act on them')).toBeTruthy();

    rerender(
      <ResultToolbar
        table={tableController({ selection: ['o-1'] })}
        fields={FIELDS}
        runtime={runtime}
        bulkActions={() => <button>Export</button>}
      />,
    );
    expect(screen.queryByText('Select rows to act on them')).toBeNull();
    expect(screen.getByRole('button', { name: 'Export' })).toBeTruthy();

    rerender(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );
    expect(container.querySelector('[data-slot="toolbar-hint"]')).toBeNull();
  });
});

/**
 * The export window (D12 Ⅳ, D14): one bordered icon button at the end of the
 * block, and one dialog behind it that carries the whole journey.
 */
describe('ResultToolbar export', () => {
  it('is not there at all when the surface offers no export', () => {
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Export' })).toBeNull();
  });

  it('opens a window that says what the file will hold', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer()}
      />,
    );

    // No dropdown: the button is the whole of the toolbar's part in it.
    expect(screen.queryByRole('menu')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Export' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading').textContent).toBe('Export');
    // How many, under what, which columns, and what it will be called.
    expect(dialog.textContent).toContain('42 records');
    expect(dialog.textContent).toContain('Conditions: All records');
    expect(dialog.textContent).toContain('1 columns: Amount');
    expect(dialog.textContent).toContain('File: Mine-2026-09-21.csv');
    // One choice is not a choice: nothing is picked, so there is no radio.
    expect(within(dialog).queryByRole('radio')).toBeNull();
  });

  it('names the conditions the rows came back under', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={{
          ...exportOffer(),
          conditions: [
            {
              path: ['0'],
              text: 'Warehouse is North',
              unresolved: false,
              field: 'warehouse',
              label: 'Warehouse',
              kind: 'string',
              operator: 'EQ',
              value: { kind: 'text', value: 'North' },
            },
          ],
        }}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));

    expect((await screen.findByRole('dialog')).textContent).toContain(
      'Conditions: Warehouse',
    );
  });

  it('offers the picked rows once there are any, and runs that scope', async () => {
    const run = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController({ selection: ['o-1', 'o-2'] })}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({ scopes: { selected: 2, all: 42 }, run })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    expect(
      within(dialog)
        .getAllByRole('radio')
        .map(radio => radio.getAttribute('aria-checked')),
    ).toEqual(['true', 'false']);
    expect(dialog.textContent).toContain('Selected (2)');
    expect(dialog.textContent).toContain(
      'All (42, under the current conditions)',
    );
    // The picked rows are the default: they are the narrower answer, and the
    // one somebody just took the trouble to make.
    expect(dialog.textContent).toContain('2 records');

    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    expect(run).toHaveBeenCalledWith('selected');
  });

  it('runs whichever scope was picked instead', async () => {
    const run = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController({ selection: ['o-1', 'o-2'] })}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({ scopes: { selected: 2, all: 42 }, run })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(
      within(dialog).getByRole('radio', {
        name: 'All (42, under the current conditions)',
      }),
    );
    await user.click(within(dialog).getByRole('button', { name: 'Export' }));

    expect(run).toHaveBeenCalledWith('all');
  });

  it('says only that the conditions are in force when nobody reports a total', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({ scopes: { all: null } })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));

    expect((await screen.findByRole('dialog')).textContent).toContain(
      'Whatever the current conditions match',
    );
  });

  it('puts the ceiling before the button rather than after the download', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({ scopes: { all: 42000 } })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));

    // Pressing Export is the consent, so what is consented to is on screen —
    // this replaces the separate question the menu used to ask first.
    expect((await screen.findByRole('dialog')).textContent).toContain(
      'That is more than the 10000 one export carries; the file will hold the first 10000.',
    );
  });

  it('shows how far a long export has got, and the way to stop it', async () => {
    const cancel = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({
          running: 'all',
          progress: { scope: 'all', fetched: 200, total: 900 },
          cancel,
        })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    const bar = within(dialog).getByRole('progressbar', { name: 'Exporting' });
    expect(bar.getAttribute('aria-valuenow')).toBe('200');
    expect(bar.getAttribute('aria-valuemax')).toBe('900');
    expect(within(dialog).getByRole('status').textContent).toBe(
      '200 of 900 fetched',
    );

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('runs the bar indeterminate while no total is known', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({
          running: 'all',
          progress: { scope: 'all', fetched: 200 },
        })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    // A bar that filled against a number nobody has would invent the number.
    expect(
      within(dialog)
        .getByRole('progressbar', { name: 'Exporting' })
        .getAttribute('aria-valuenow'),
    ).toBeNull();
    expect(within(dialog).getByRole('status').textContent).toBe('200 fetched');
  });

  it('reports what the file holds, and what the ceiling left out', async () => {
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({
          outcome: { scope: 'all', rows: 10000, capped: true, total: 42000 },
        })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    expect(dialog.textContent).toContain('10000 records exported');
    expect(dialog.textContent).toContain('File: Mine-2026-09-21.csv');
    expect(dialog.textContent).toContain(
      'The file holds the first 10000 of the 42000 records that match.',
    );
    // By slot rather than by name: the vendored dialog draws its own ✕ with
    // the same word in it, and both do close the window.
    expect(
      dialog.querySelector('[data-slot="export-close"]')?.textContent,
    ).toBe('Close');
  });

  it('offers the failure and a way to try it again', async () => {
    const run = vi.fn();
    const user = userEvent.setup();
    render(
      <ResultToolbar
        table={tableController()}
        fields={FIELDS}
        runtime={runtime}
        exporter={exportOffer({
          error: {
            code: 'export.failed',
            path: [],
            params: { reason: 'gateway down' },
            severity: 'error',
          },
          run,
        })}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Export' }));
    const dialog = await screen.findByRole('dialog');

    expect(dialog.textContent).toContain('The export failed. gateway down');
    await user.click(within(dialog).getByRole('button', { name: 'Try again' }));
    expect(run).toHaveBeenCalledWith('all');
  });
});
