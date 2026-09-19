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
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { FilterSummaryItem } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import type { FilterEditorController } from '../src/react/index.js';
import { AppliedBar } from '../src/ui/AppliedBar.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * A controller with only what the bar reads. The bar describes what came
 * back and offers one action per item; everything else on the controller is
 * the editor's business, and a stub says so.
 */
function stub(
  applied: FilterSummaryItem[],
  actions: Partial<FilterEditorController> = {},
): FilterEditorController {
  return {
    applied,
    scoped: [],
    clearValue: vi.fn(),
    submit: vi.fn(),
    ...actions,
  } as unknown as FilterEditorController;
}

/** The bar over a live runtime, with the editor in reach. */
function overRuntime() {
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => testSource(),
  });
  const runtime = engine.create('orders', {
    title: 'Scratch',
    scope: 'personal',
    config: recordConfig(),
  });
  let latest: FilterEditorController | null = null;
  function Probe() {
    const filter = useFilterEditor(runtime);
    latest = filter;
    return (
      <AppliedBar
        filter={filter}
        hasResult={runtime.getSnapshot().result !== null}
      />
    );
  }
  render(<Probe />);
  return { filter: () => latest as FilterEditorController };
}

describe('AppliedBar', () => {
  it('says nothing before a result exists to describe', () => {
    // An empty `applied` means "no condition" *or* "no answer yet", and the
    // bar cannot tell them apart on its own — so it is told.
    const { container } = render(
      <AppliedBar filter={stub([])} hasResult={false} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('says so plainly when the result ran under no condition at all', () => {
    render(<AppliedBar filter={stub([])} hasResult />);

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('All records');
  });

  it('lists the conditions the rows in front of you were fetched under', () => {
    render(
      <AppliedBar
        filter={stub([
          {
            path: ['children', 0],
            text: 'Warehouse EQ CN',
            unresolved: false,
            field: 'warehouse',
            label: 'Warehouse',
          },
        ])}
        hasResult
      />,
    );

    expect(screen.getByText('Warehouse EQ CN')).toBeDefined();
    expect(screen.queryByText('All records')).toBeNull();
  });

  it('wears a condition whose field is gone plainly, and marks it', () => {
    render(
      <AppliedBar
        filter={stub([
          {
            path: ['children', 0],
            text: 'legacy EQ 1',
            unresolved: true,
            field: 'legacy',
            label: 'legacy',
          },
        ])}
        hasResult
      />,
    );

    // It is still in force, so it is named rather than hidden; it is not
    // something to go on building on, so it is not dressed as one.
    const badge = screen.getByText('legacy EQ 1');
    expect(badge.hasAttribute('data-unresolved')).toBe(true);
    expect(badge.className).toContain('border-border');
  });

  it('takes a condition out of force from its badge, keeping the field', async () => {
    const { filter } = overRuntime();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });
    await waitFor(() =>
      expect(screen.getByText('Warehouse EQ CN')).toBeDefined(),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Unset Warehouse EQ CN' }),
    );

    await waitFor(() =>
      expect(screen.queryByText('Warehouse EQ CN')).toBeNull(),
    );
    // The condition left the query; the row is still in the editor, blank,
    // for the next question.
    expect(filter().applied).toEqual([]);
    expect(filter().tree.children[0]).toMatchObject({ field: 'warehouse' });
  });

  it('clears the value and re-runs in one go', () => {
    const clearValue = vi.fn();
    const submit = vi.fn();
    render(
      <AppliedBar
        filter={stub(
          [
            {
              path: ['children', 1, 'children', 0],
              text: 'Amount GT 10',
              unresolved: false,
            },
          ],
          { clearValue, submit },
        )}
        hasResult
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Unset Amount GT 10' }));

    // The editor addresses nodes by index; the `children` keys of a summary
    // path are not part of the address.
    expect(clearValue).toHaveBeenCalledWith([1, 0]);
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('freezes its removes with the rest of the view', () => {
    render(
      <AppliedBar
        filter={stub([
          { path: ['children', 0], text: 'Warehouse EQ CN', unresolved: false },
        ])}
        hasResult
        disabled
      />,
    );

    expect(
      (
        screen.getByRole('button', {
          name: 'Unset Warehouse EQ CN',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });
  /**
   * The host's own conditions are in force beside the view's own, and the
   * editor has no path that reaches them: they are not in the draft, and
   * `clearValue` cannot address them. So they are named — a reader who
   * cannot see why the list is short learns nothing — and they carry no ✕,
   * because the only honest thing a ✕ could do here is fail.
   */
  it("names the host scope after the view's own, without a way to remove it", () => {
    render(
      <AppliedBar
        filter={stub(
          [
            {
              path: ['children', 0],
              text: 'Warehouse EQ CN',
              unresolved: false,
            },
          ],
          {
            scoped: [
              {
                path: ['children', 0],
                text: 'Customer EQ c-1',
                unresolved: false,
              },
            ],
          },
        )}
        hasResult
      />,
    );

    const badges = [...document.querySelectorAll('[data-slot="badge"]')];
    expect(badges.map(badge => badge.textContent?.trim())).toEqual([
      'Warehouse EQ CN',
      'Customer EQ c-1 Set by the page',
    ]);
    // The view's own is removable and dressed as a condition; the page's is
    // worn plainly and offers nothing to press.
    expect(badges[0].hasAttribute('data-scoped')).toBe(false);
    expect(badges[1].hasAttribute('data-scoped')).toBe(true);
    expect(badges[1].className).toContain('border-border');
    expect(
      screen.queryByRole('button', { name: 'Unset Customer EQ c-1' }),
    ).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Unset Warehouse EQ CN' }),
    ).toBeDefined();
  });

  /**
   * "All records" answers for everything in force, and a scope is in force.
   * Saying it beside a narrowing the page applied would be the bar telling
   * the reader the opposite of what the rows below it are.
   */
  it('does not call a scoped result all of them', () => {
    render(
      <AppliedBar
        filter={stub([], {
          scoped: [
            {
              path: ['children', 0],
              text: 'Customer EQ c-1',
              unresolved: false,
            },
          ],
        })}
        hasResult
      />,
    );

    expect(screen.queryByText('All records')).toBeNull();
    expect(screen.getByText(/Customer EQ c-1/)).toBeDefined();
  });

  /**
   * Read-only is not "disabled": an embedded view shows what its author
   * saved, and a ✕ that is only ever grey still tells the reader there is a
   * condition here they might get to drop.
   */
  it('renders no remove at all when it is read-only', () => {
    render(
      <AppliedBar
        filter={stub([
          { path: ['children', 0], text: 'Warehouse EQ CN', unresolved: false },
        ])}
        hasResult
        readOnly
      />,
    );

    expect(screen.getByText('Warehouse EQ CN')).toBeDefined();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
