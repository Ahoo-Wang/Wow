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
 * One window for the whole journey (D14), driven on its own.
 *
 * `test/resultToolbar.test.tsx` asks whether the toolbar puts this control at
 * the end of its right-hand block and whether the window opens; the export
 * from the button to the blob is `test/recordExportUi.test.tsx`. What is
 * asked here is the window's own promise — which is mostly about the answers
 * a source gives when it cannot count: no total to offer, no total to report,
 * no progress to draw. Those are the states a real store reaches on a cursor
 * source, and the ones no end-to-end suite can arrange.
 */

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordColumnView } from '../src/record/index.js';
import type { RecordExportController } from '../src/react/index.js';
import { ExportDialog, ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

const COLUMNS: readonly RecordColumnView[] = [
  {
    field: 'amount',
    label: 'Amount',
    kind: 'number',
    cell: 'number',
    sortable: true,
  },
];

function control(
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

/** The window, opened, with whatever the controller is saying at the time. */
async function open(
  overrides: Partial<RecordExportController> = {},
  nameFile = () => 'Mine-2026-09-21.csv',
): Promise<{ dialog: HTMLElement; user: ReturnType<typeof userEvent.setup> }> {
  const user = userEvent.setup();
  render(
    <ViewSurface>
      <ExportDialog
        control={control(overrides)}
        conditions={[]}
        nameFile={nameFile}
        columns={COLUMNS}
        max={10000}
      />
    </ViewSurface>,
  );
  await user.click(screen.getByRole('button', { name: 'Export' }));
  return { dialog: await screen.findByRole('dialog'), user };
}

describe('the export window with nothing to count', () => {
  /**
   * A cursor source reports no total, and a paged one may withhold it. The
   * window says what it can — the conditions are in force — rather than
   * inventing a number or standing empty where one would go.
   */
  it('promises what the conditions match rather than a count', async () => {
    const { dialog } = await open({ scopes: { all: null } });

    expect(
      within(dialog).getByText('Whatever the current conditions match'),
    ).toBeDefined();
    // And no ceiling warning: nothing said it was over one.
    expect(dialog.querySelector('[data-slot="export-over-limit"]')).toBeNull();
  });

  /** The same on the radio, where a count would otherwise be in brackets. */
  it('offers the uncounted scope beside the rows that are picked', async () => {
    const { dialog } = await open({ scopes: { selected: 2, all: null } });

    // The radio renders as a span, so each is named by the label beside it.
    expect(
      within(dialog)
        .getAllByRole('radio')
        .map(radio => radio.getAttribute('data-scope')),
    ).toEqual(['selected', 'all']);
    expect(
      within(dialog).getByRole('radio', {
        name: 'All (under the current conditions)',
      }),
    ).toBeDefined();
    expect(dialog.textContent).toContain('Selected (2)');
  });
});

describe('what the export window picks', () => {
  /** The picked rows are the default, and the choice goes back and forth. */
  it('goes from the picked rows to everything and back', async () => {
    const run = vi.fn();
    const { dialog, user } = await open({
      scopes: { selected: 2, all: 42 },
      run,
    });

    expect(dialog.textContent).toContain('2 records');
    await user.click(
      within(dialog).getByRole('radio', {
        name: 'All (42, under the current conditions)',
      }),
    );
    expect(dialog.textContent).toContain('42 records');

    await user.click(
      within(dialog).getByRole('radio', { name: 'Selected (2)' }),
    );
    expect(dialog.textContent).toContain('2 records');

    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    expect(run).toHaveBeenCalledWith('selected', 'Mine-2026-09-21.csv');
  });

  /**
   * The name is settled as the window opens and used by every step of the one
   * journey, so a window left open across midnight cannot promise one name
   * and hand over another (D14).
   */
  it('asks what the file is called once, as it opens', async () => {
    const nameFile = vi.fn(() => 'Mine-2026-09-21.csv');
    const { dialog } = await open(
      { scopes: { selected: 2, all: 42 } },
      nameFile,
    );

    expect(nameFile).toHaveBeenCalledTimes(1);
    expect(dialog.textContent).toContain('File: Mine-2026-09-21.csv');
  });
});

describe('the export window while it runs', () => {
  /**
   * A bar filled against a number nobody has would be inventing the number,
   * so it is indeterminate — and the caption beside it counts what is in hand
   * rather than reading as a second, different answer.
   */
  it('draws an indeterminate bar where no total was reported', async () => {
    const { dialog } = await open({ running: 'all', progress: null });

    const bar = within(dialog).getByRole('progressbar', { name: 'Exporting' });
    expect(bar.getAttribute('aria-valuenow')).toBeNull();
    expect(bar.getAttribute('aria-valuetext')).toBe('0 fetched');
    expect(
      dialog.querySelector('[data-slot="export-count"]')?.textContent,
    ).toBe('0 fetched');
  });

  /** Escape is an answer while the pages come in: stop. */
  it('stops the run when the window is dismissed', async () => {
    const cancel = vi.fn();
    const reset = vi.fn();
    const { user } = await open({ running: 'all', cancel, reset });

    await user.keyboard('{Escape}');

    expect(cancel).toHaveBeenCalledTimes(1);
    // Stopping is not forgetting: what the run produced is still the
    // controller's to report.
    expect(reset).not.toHaveBeenCalled();
  });
});

describe('how the export window ends', () => {
  /**
   * A file the ceiling cut short is the file that was agreed to, so it is
   * said as part of the outcome — and where the source reports no total, it
   * says only that there is more rather than how much more.
   */
  it('says the ceiling was reached without a total nobody has', async () => {
    const { dialog } = await open({
      outcome: { scope: 'all', rows: 10000, capped: true },
    });

    expect(dialog.textContent).toContain('10,000 records exported');
    expect(
      dialog.querySelector('[data-slot="export-capped"]')?.textContent,
    ).toBe('The file holds the first 10,000 records; more match than that.');
  });

  /**
   * Closing forgets what the last run produced, so the next opening asks
   * again rather than reporting an export that has already been read. Escape
   * has nothing to stop here, so it is the same answer as the button.
   */
  it('forgets the outcome when the window is dismissed after one', async () => {
    const reset = vi.fn();
    const { user } = await open({
      outcome: { scope: 'all', rows: 42, capped: false },
      reset,
    });

    await user.keyboard('{Escape}');

    expect(reset).toHaveBeenCalledTimes(1);
  });

  /** A failure is offered again under the name the first attempt carried. */
  it('offers a failed export again, under the same name', async () => {
    const run = vi.fn();
    const reset = vi.fn();
    const { dialog, user } = await open({
      error: { code: 'export.failed', severity: 'error', path: [] },
      run,
      reset,
    });

    expect(dialog.querySelector('[data-slot="export-progress"]')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Try again' }));

    expect(reset).toHaveBeenCalledTimes(1);
    expect(run).toHaveBeenCalledWith('all', 'Mine-2026-09-21.csv');
  });
});
