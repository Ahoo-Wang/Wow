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
 * A form the source refused what was typed in (`ActionInputError`, UI walk
 * UI-13): it comes back with the input, the refused fields marked with the
 * source's reasons and the keyboard on the first, over the records it was
 * refused for; any other failure closes it, as before.
 */

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ActionInputError,
  actions,
  type RecordAction,
  type RecordActions,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { setup } from './fixtures/ui.js';

afterEach(cleanup);

/** A form of a count and a duration, as an ops service's retry spec. */
function retrySpec(run: RecordAction['run']): RecordAction {
  return {
    id: 'spec',
    label: 'Apply spec',
    form: {
      count: { label: 'Count', required: false },
      backoff: { label: 'Backoff', required: false },
    },
    run,
  };
}

async function open(declared: RecordActions) {
  const harness = setup();
  render(
    <DataWorkbench
      engine={harness.engine}
      definitionId="orders"
      instanceId="orders-1"
      record={{ actions: declared }}
    />,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
}

function rowOf(key: string) {
  return screen
    .getAllByRole('row')
    .find(row => within(row).queryByLabelText(`Select ${key}`))!;
}

/** What an element is described by, as a reader hears it. */
function described(element: HTMLElement): string {
  return (element.getAttribute('aria-describedby') ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map(id => document.getElementById(id)?.textContent ?? '')
    .join(' ');
}

async function fill(key: string, values: Record<string, string>) {
  await userEvent.click(
    within(rowOf(key)).getByRole('button', { name: `Actions for ${key}` }),
  );
  await userEvent.click(
    await screen.findByRole('menuitem', { name: 'Apply spec' }),
  );
  const dialog = await screen.findByRole('dialog');
  for (const [label, value] of Object.entries(values))
    fireEvent.change(within(dialog).getByLabelText(label), {
      target: { value },
    });
  await userEvent.click(
    within(dialog).getByRole('button', { name: 'Apply spec' }),
  );
}

function status() {
  return document.querySelector<HTMLElement>('[data-slot="bulk-status"]');
}

describe('a form the source refused what was typed in', () => {
  it('comes back with the input, the refused field marked with the source’s reason and the keyboard on it', async () => {
    const run = vi
      .fn<RecordAction['run']>()
      .mockRejectedValueOnce(
        new ActionInputError('Not a duration: 1m 1h.', {
          backoff: 'Not a duration: 1m 1h.',
        }),
      )
      .mockResolvedValue(undefined);
    await open(actions([retrySpec(run)]));
    await fill('o-1', { Count: '3', Backoff: '1m 1h' });

    const dialog = await screen.findByRole('dialog', {
      name: 'Apply spec: o-1',
    });
    const backoff = within(dialog).getByLabelText('Backoff');
    await waitFor(() => expect(document.activeElement).toBe(backoff));
    expect((backoff as HTMLInputElement).value).toBe('1m 1h');
    expect(
      (within(dialog).getByLabelText('Count') as HTMLInputElement).value,
    ).toBe('3');
    expect(backoff.getAttribute('aria-invalid')).toBe('true');
    expect(described(backoff)).toBe('Not a duration: 1m 1h.');
    // Named under its field, the reason is not said again above the form.
    expect(dialog.querySelector('[data-slot="action-returned"]')).toBeNull();
    // The outcome line still says what came of the run.
    expect(status()?.textContent).toContain('failed: Not a duration: 1m 1h.');

    // The same value is not sent again; another is.
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Apply spec' }),
    );
    expect(run).toHaveBeenCalledTimes(1);
    fireEvent.change(backoff, { target: { value: '1h 1m' } });
    expect(backoff.getAttribute('aria-invalid')).not.toBe('true');
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Apply spec' }),
    );
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    expect(run).toHaveBeenLastCalledWith(
      expect.objectContaining({ key: 'o-1' }),
      { count: '3', backoff: '1h 1m' },
      { signal: expect.any(AbortSignal) },
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('says a reason that names no field above the form, and keeps what was typed', async () => {
    const run = vi.fn<RecordAction['run']>(() =>
      Promise.reject(
        new ActionInputError('The spec is not valid.', { other: 'Ignored.' }),
      ),
    );
    await open(actions([retrySpec(run)]));
    await fill('o-1', { Backoff: 'PT1.5M' });

    const dialog = await screen.findByRole('dialog', {
      name: 'Apply spec: o-1',
    });
    const returned = dialog.querySelector<HTMLElement>(
      '[data-slot="action-returned"]',
    );
    expect(returned?.getAttribute('role')).toBe('alert');
    expect(returned?.getAttribute('data-tone')).toBe('danger');
    expect(returned?.textContent).toBe('The spec is not valid.');
    const backoff = within(dialog).getByLabelText('Backoff');
    expect((backoff as HTMLInputElement).value).toBe('PT1.5M');
    expect(backoff.getAttribute('aria-invalid')).not.toBe('true');
  });

  it('closes on any other failure, said on the outcome line', async () => {
    const run = vi.fn<RecordAction['run']>(() =>
      Promise.reject(new Error('The entry is resolved.')),
    );
    await open(actions([retrySpec(run)]));
    await fill('o-1', { Backoff: '3m' });

    await waitFor(() =>
      expect(status()?.textContent).toContain('failed: The entry is resolved.'),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('comes back over the records of a selection it was refused for, the rest done', async () => {
    const run = vi.fn<RecordAction['run']>(row =>
      row.key === 'o-2'
        ? Promise.reject(
            new ActionInputError('Too many.', { count: 'At most 5.' }),
          )
        : Promise.resolve(),
    );
    await open(actions([retrySpec(run)]));
    await userEvent.click(screen.getByLabelText('Select o-1'));
    await userEvent.click(screen.getByLabelText('Select o-2'));
    // Not primary: the bar names it without a count.
    await userEvent.click(screen.getByRole('button', { name: 'Apply spec' }));
    const asked = await screen.findByRole('dialog');
    fireEvent.change(within(asked).getByLabelText('Count'), {
      target: { value: '9' },
    });
    await userEvent.click(
      within(asked).getByRole('button', { name: 'Apply spec 2' }),
    );

    await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    const dialog = await screen.findByRole('dialog', {
      name: 'Run “Apply spec” on o-2?',
    });
    const count = within(dialog).getByLabelText('Count');
    expect((count as HTMLInputElement).value).toBe('9');
    expect(described(count)).toBe('At most 5.');
  });

  it('does not come back for a choice, which draws no form', async () => {
    const run = vi.fn<RecordAction['run']>(() =>
      Promise.reject(new ActionInputError('Not that one.')),
    );
    await open(
      actions([
        {
          id: 'priority',
          label: 'Set priority',
          form: {
            priority: {
              label: 'Priority',
              options: [
                { value: 'HIGH', label: 'High' },
                { value: 'LOW', label: 'Low' },
              ],
            },
          },
          run,
        },
      ]),
    );
    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'High' }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('failed: Not that one.'),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
