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
  within,
} from '@testing-library/react';
import { renderHook } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
} from '../src/index.js';
import type {
  FieldKind,
  FilterTree,
  OptionSource,
  ViewInstance,
} from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import {
  FilterPanel,
  MessagesProvider,
  DataWorkbench,
  zhCN,
} from '../src/ui/index.js';
import type { FilterPanelProps, ViewMessages } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { describedText, mine, mixed, setup } from './fixtures/ui.js';

afterEach(cleanup);

describe('FilterPanel tree editing', () => {
  interface PanelHarness {
    filter(): ReturnType<typeof useFilterEditor>;
  }

  /** A definition whose `items` array declares what its entries hold. */
  function withItems() {
    const base = ordersDefinition();
    return {
      ...base,
      fields: [
        ...base.fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'elementMatch' as const,
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' as const },
            { name: 'qty', label: 'Qty', kind: 'number' as const },
          ],
        },
      ],
    };
  }

  function panel(
    disabled = false,
    definition = ordersDefinition(),
    messages?: ViewMessages,
    /** What the surface around the panel has taken off its hands. */
    props: Partial<FilterPanelProps> = {},
    /** The host's option sources, for a definition with a reference field. */
    resolveOptions?: () => OptionSource,
  ): PanelHarness {
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      ...(resolveOptions ? { resolveOptions } : {}),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    // The panel must re-render with the controller on every runtime commit,
    // so both live inside one component rather than two renders.
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} disabled={disabled} {...props} />;
    }
    render(
      <MessagesProvider messages={messages}>
        <Probe />
      </MessagesProvider>,
    );
    return { filter: () => latest as ReturnType<typeof useFilterEditor> };
  }

  /**
   * The field picker, opened. It is a popover (a dialog) that stays open
   * while several fields are ticked, so every field question below is asked
   * of the one dialog rather than of the page. Its trigger is an ordinary
   * button named by its own text.
   */
  async function openPicker(
    name = 'Add',
    // Every group carries an "Add condition" of its own, so a nested
    // one's is reached through the group rather than through the page.
    from: HTMLElement = document.body,
  ): Promise<HTMLElement> {
    fireEvent.click(within(from).getByRole('button', { name }));
    return screen.findByRole('dialog', { name: 'Choose fields' });
  }

  /** Ticking a field is what adds its condition; Done is the way out. */
  async function add(
    fields: string[],
    name = 'Add',
    from?: HTMLElement,
  ): Promise<void> {
    const picker = await openPicker(name, from);
    for (const field of fields)
      fireEvent.click(within(picker).getByRole('checkbox', { name: field }));
    fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }

  /** A group's operator, chosen from the select that shows one at a time. */
  async function choose(name: string, option: string): Promise<void> {
    const user = userEvent.setup();
    await user.click(screen.getByRole('combobox', { name }));
    await user.click(await screen.findByRole('option', { name: option }));
  }

  it('lists the fields of the picker by the groups the definition declares', async () => {
    const grouped = ordersDefinition({
      fieldGroups: [
        { id: 'state', label: 'State', fields: ['status'] },
        { id: 'money', label: 'Money', fields: ['amount'] },
      ],
    });
    panel(false, grouped);

    const picker = await openPicker();
    const text =
      picker.querySelector('[data-slot="field-checklist"]')?.textContent ?? '';

    // Ungrouped fields first, then each declared group under its label, in
    // the catalogue's order rather than the fields' own.
    const at = (word: string) => text.indexOf(word);
    expect(at('Order')).toBeGreaterThanOrEqual(0);
    expect(at('Order')).toBeLessThan(at('State'));
    expect(at('State')).toBeLessThan(at('Status'));
    expect(at('Status')).toBeLessThan(at('Money'));
    expect(at('Money')).toBeLessThan(at('Amount'));
  });

  it('narrows the fields to what is typed, across every group', async () => {
    panel();
    const picker = await openPicker();
    const search = within(picker).getByRole('textbox', {
      name: 'Search fields',
    });

    fireEvent.change(search, { target: { value: 'sta' } });

    await waitFor(() =>
      expect(within(picker).getAllByRole('checkbox')).toHaveLength(1),
    );
    expect(
      within(picker).getByRole('checkbox', { name: 'Status' }),
    ).toBeTruthy();
  });

  /**
   * A group holds at most one condition per field, and the list says so by
   * showing the field ticked rather than by dropping it: the tick is the
   * condition's existence, which is what makes unticking mean "take it away".
   */
  it('shows a field already in the group as ticked, not as a second entry', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const picker = await openPicker();

    const box = (name: string) =>
      within(picker).getByRole('checkbox', { name });
    expect(
      within(picker).getAllByRole('checkbox', { name: 'Warehouse' }),
    ).toHaveLength(1);
    expect(box('Warehouse').getAttribute('aria-checked')).toBe('true');
    expect(box('Status').getAttribute('aria-checked')).toBe('false');
  });

  /** The other half of the same tick: clearing one takes the condition out. */
  it('removes the condition of a field that is unticked', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const picker = await openPicker();
    fireEvent.click(
      within(picker).getByRole('checkbox', { name: 'Warehouse' }),
    );

    await waitFor(() => expect(filter().tree.children).toHaveLength(0));
  });

  /**
   * Building a filter is choosing several fields, and a menu that closed on
   * each one made that one round trip per condition.
   */
  it('stays open while more than one field is ticked', async () => {
    const { filter } = panel();

    const picker = await openPicker();
    fireEvent.click(
      within(picker).getByRole('checkbox', { name: 'Warehouse' }),
    );
    fireEvent.click(within(picker).getByRole('checkbox', { name: 'Status' }));

    expect(
      filter().tree.children.map(child => (child as { field: string }).field),
    ).toEqual(['warehouse', 'status']);
    expect(screen.getByRole('dialog', { name: 'Choose fields' })).toBeDefined();

    fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  /**
   * The grid is worked with the keyboard alone, which is the whole reason a
   * checkbox is a checkbox: focus opens on the search line, Tab steps into
   * the fields, and Space is what ticking one means.
   */
  it('ticks a field with the keyboard alone', async () => {
    const user = userEvent.setup();
    const { filter } = panel();
    const picker = await openPicker();

    await user.tab();
    expect(document.activeElement).toBe(
      within(picker).getByRole('checkbox', { name: 'Order' }),
    );
    await user.keyboard(' ');

    await waitFor(() =>
      expect(
        filter().tree.children.map(child => (child as { field: string }).field),
      ).toEqual(['id']),
    );
  });

  it('leaves the applied summary to the bar that owns it', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });

    // The panel is the draft and nothing else. What ran is described beside
    // the rows it fetched, where it can be read against them.
    expect(document.querySelectorAll('[data-slot="badge"]')).toHaveLength(0);
    expect(document.querySelector('[data-slot="applied-bar"]')).toBeNull();
  });

  it('puts the way in and the way out in one row under the tree', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));
    const actions = document.querySelector(
      '[data-slot="filter-actions"]',
    ) as HTMLElement;

    // Everything that acts on the tree sits in one row under the tree it
    // acts on.
    expect(within(actions).getByRole('button', { name: 'Add' })).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: 'Clear' }),
    ).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: /Apply/ }),
    ).toBeDefined();
    // The mode is not one of them: it is a way of editing rather than a
    // thing done to the tree, so it keeps its own place at the top.
    expect(
      within(actions).queryByRole('button', { name: 'Advanced' }),
    ).toBeNull();
    const conditions = document.querySelector(
      '[data-slot="filter-conditions"]',
    ) as HTMLElement;
    expect(
      actions.compareDocumentPosition(conditions) &
        Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy();
    // A bare `minmax(20rem, …)` is a floor the track keeps even when the
    // band is narrower than it, so at a phone's width every column stayed
    // 320px and the pills hung past the editor band. Clamped to the band,
    // the column is 20rem where there is 20rem and the band's width where
    // there is not.
    // A **surviving class assertion**: how many condition columns fit is a
    // grid template, and the number of columns it comes to at a given width
    // is the browser story's to count.
    expect(conditions.className).toContain('minmax(min(20rem,100%),1fr)');
  });

  it('keeps the fields to add with when it has no way out of its own', () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} submit={false} />;
    }
    render(<Probe />);

    // An editor applied from elsewhere keeps its fields and loses the pair
    // that would run the query a second time.
    expect(screen.getByRole('button', { name: 'Add' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
  });

  it('keeps the mode reachable when nothing outside offers it', () => {
    panel();

    // The default. A workbench whose editor is this panel and something
    // else besides cannot fold both under the word "Filter", so the choice
    // stays here — a mode that exists but cannot be reached is a capability
    // lost rather than a tidier screen.
    const root = screen.getByRole('region', { name: 'Filter' });
    expect(
      within(root).getByRole('button', { name: 'Advanced' }),
    ).toBeDefined();
  });

  it('gives the mode up to a surface that has taken it', () => {
    panel(false, ordersDefinition(), undefined, { modes: false });

    const root = screen.getByRole('region', { name: 'Filter' });
    expect(within(root).queryByRole('button', { name: 'Simple' })).toBeNull();
    expect(within(root).queryByRole('button', { name: 'Advanced' })).toBeNull();
  });

  it('marks a condition, and the button that would run it, as not applied', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));
    const pill = () =>
      screen.getByRole('group', { name: 'Warehouse condition' });
    const apply = () => screen.getByRole('button', { name: /Apply/ });

    // A draft is only worth keeping apart from what ran if the difference is
    // visible, and it is visible on the condition that carries it.
    expect(pill().hasAttribute('data-pending')).toBe(true);
    expect(within(pill()).getByText('Not applied yet')).toBeDefined();
    expect(apply().hasAttribute('data-pending')).toBe(true);

    act(() => filter().submit());

    await waitFor(() =>
      expect(pill().hasAttribute('data-pending')).toBe(false),
    );
    expect(apply().hasAttribute('data-pending')).toBe(false);
  });

  /**
   * The other end of the same decision Apply is, and only while there is one
   * to end: a button that would change nothing teaches nothing.
   */
  it('offers to discard the edits only while there are some', async () => {
    const { filter } = panel();
    expect(screen.queryByRole('button', { name: 'Discard edits' })).toBeNull();

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    fireEvent.click(screen.getByRole('button', { name: 'Discard edits' }));

    // Back to what the rows on screen were fetched under, which here is
    // nothing at all — and with nothing pending the way back goes too.
    await waitFor(() => expect(filter().tree.children).toHaveLength(0));
    expect(screen.queryByRole('button', { name: 'Discard edits' })).toBeNull();
  });

  /** Enter in a value editor is the same command the Apply button runs. */
  it('applies on Enter from a value editor', () => {
    const { filter } = panel();
    const submit = vi.fn();
    act(() => filter().addLeaf('warehouse'));
    const value = screen.getByLabelText('Warehouse value');
    vi.spyOn(filter(), 'submit').mockImplementation(submit);

    fireEvent.keyDown(value, { key: 'Enter' });

    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('leaves Enter alone on a control that answers it itself', () => {
    const { filter } = panel();
    const submit = vi.fn();
    act(() => filter().addLeaf('warehouse'));
    vi.spyOn(filter(), 'submit').mockImplementation(submit);

    // Enter on "Add" opens the field picker: one keystroke, one meaning.
    fireEvent.keyDown(screen.getByRole('button', { name: 'Add' }), {
      key: 'Enter',
    });

    expect(submit).not.toHaveBeenCalled();
  });

  /**
   * A numeric `IN` compiles an array of any length, and it was the editor
   * that capped it at two by borrowing the range's pair of boxes. The rule
   * is asked of the panel rather than of the kernel because the kernel never
   * broke: a kind-level case passed all along while the third value had
   * nowhere on screen to go.
   */
  it('builds a numeric IN of as many values as are entered', async () => {
    const user = userEvent.setup();
    const { filter } = panel();
    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { operator: 'IN' });
    });
    const leaf = () => filter().tree.children[0] as { value: unknown };
    const entry = () => screen.getByLabelText('New Amount value');

    await user.type(entry(), '1{Enter}');
    await user.type(entry(), '2{Enter}');
    // Enter in here adds a value; it is not also the panel's apply, which
    // would run the query on a list still being written.
    const submit = vi.fn();
    await user.type(entry(), '3');
    vi.spyOn(filter(), 'submit').mockImplementation(submit);
    await user.keyboard('{Enter}');

    await waitFor(() => expect(leaf().value).toEqual([1, 2, 3]));
    expect(submit).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Remove 2' }));
    await waitFor(() => expect(leaf().value).toEqual([1, 3]));

    // An empty entry is not a value: Enter on it adds nothing, and is still
    // this field's own keystroke rather than the panel's.
    await user.keyboard('{Enter}');
    expect(leaf().value).toEqual([1, 3]);
    expect(submit).not.toHaveBeenCalled();
  });

  /**
   * Apply is a button elsewhere on the panel, and reaching for it blurs the
   * entry field first — so a number typed and not yet added was dropped by
   * the very click meant to run the query with it.
   */
  it('applies a number still in the entry field when Apply is pressed', async () => {
    const user = userEvent.setup();
    const { filter } = panel();
    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { operator: 'IN', value: [1] });
    });

    await user.type(screen.getByLabelText('New Amount value'), '7');
    // While the entry's popup offers «Add 7», Base UI hides the rest of the
    // page from readers, as every typeable combobox does; the button is
    // still there for a pointer.
    await user.click(
      screen.getByRole('button', { name: /Apply/, hidden: true }),
    );

    await waitFor(() =>
      expect(filter().applied.map(item => item.text)).toEqual([
        'Amount IN 1, 7',
      ]),
    );
  });

  /**
   * Discard is the panel's undo, and it used to leave the number list's
   * entry field holding what was being typed when it ran — so the next blur
   * wrote that number back into the list the discard had just restored.
   */
  it('discards a number that was being typed along with the edits', async () => {
    const user = userEvent.setup();
    const { filter } = panel();
    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { operator: 'IN', value: [1] });
    });
    act(() => filter().submit());
    const entry = () => screen.getByLabelText('New Amount value');
    await waitFor(() =>
      expect(filter().applied.map(item => item.text)).toEqual(['Amount IN 1']),
    );

    await user.type(entry(), '2{Enter}');
    await waitFor(() =>
      expect((filter().tree.children[0] as { value: unknown }).value).toEqual([
        1, 2,
      ]),
    );
    // Typed and not added, and the discard is pressed without leaving the
    // field — otherwise the blur would commit it before the undo ran.
    await user.type(entry(), '3');
    fireEvent.click(
      screen.getByRole('button', { name: 'Discard edits', hidden: true }),
    );

    await waitFor(() =>
      expect((filter().tree.children[0] as { value: unknown }).value).toEqual([
        1,
      ]),
    );
    expect((entry() as HTMLInputElement).value).toBe('');

    // Apply blurs the entry on its way, and that blur has nothing to add.
    await user.click(screen.getByRole('button', { name: /Apply/ }));
    await waitFor(() =>
      expect(filter().applied.map(item => item.text)).toEqual(['Amount IN 1']),
    );
  });

  /**
   * The panel listens at its root, so keystrokes reach it that were never
   * meant for it. Each of these is one of those, and each has its own
   * reason for not being an apply.
   */
  it.each([
    // An IME uses Enter to accept the characters being composed. As far as
    // the user is concerned that is not a press of Enter at all.
    ['while an IME is composing', { key: 'Enter', isComposing: true }],
    // Enter with a modifier is some other shortcut, possibly the host's.
    ['with a modifier held', { key: 'Enter', shiftKey: true }],
    ['with the platform modifier held', { key: 'Enter', metaKey: true }],
    ['with control held', { key: 'Enter', ctrlKey: true }],
    ['with alt held', { key: 'Enter', altKey: true }],
    // Any other key is nobody's business but the input's.
    ['on any other key', { key: 'a' }],
  ])('does not apply %s', (_name, init) => {
    const { filter } = panel();
    const submit = vi.fn();
    act(() => filter().addLeaf('warehouse'));
    const value = screen.getByLabelText('Warehouse value');
    vi.spyOn(filter(), 'submit').mockImplementation(submit);

    fireEvent.keyDown(value, init);

    expect(submit).not.toHaveBeenCalled();
  });

  it('does not apply from inside a popup one of its controls opened', () => {
    const { filter } = panel();
    const submit = vi.fn();
    act(() => filter().addLeaf('warehouse'));
    const value = screen.getByLabelText('Warehouse value');
    vi.spyOn(filter(), 'submit').mockImplementation(submit);

    // Base UI marks the trigger of an open popup, which is the same mark
    // `leavesEditor` reads. While one is open, Enter is its answer to give.
    const trigger = screen.getByRole('button', { name: 'Add' });
    trigger.setAttribute('data-popup-open', '');
    fireEvent.keyDown(value, { key: 'Enter' });

    expect(submit).not.toHaveBeenCalled();
  });

  it('refuses Enter on exactly the terms the Apply button refuses', () => {
    const { filter } = panel(true);
    const submit = vi.fn();
    act(() => filter().addLeaf('warehouse'));
    const value = screen.getByLabelText('Warehouse value');
    vi.spyOn(filter(), 'submit').mockImplementation(submit);

    // Disabled here; `blocked` is the other half of the same rule, and the
    // button that will not move is the one this shortcut stands in for.
    fireEvent.keyDown(value, { key: 'Enter' });

    expect(submit).not.toHaveBeenCalled();
  });

  it('marks a group that was flipped since the last apply', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addGroup('or');
    });

    const group = screen.getByRole('group', { name: 'Any condition' });
    expect(group.hasAttribute('data-pending')).toBe(true);
  });

  it('marks nothing inside a predicate, which has nothing to compare against', async () => {
    const { filter } = panel(false, withItems());
    act(() => filter().addLeaf('items'));

    // The outer condition is new, so it is pending; the tree it carries is
    // edited straight into that leaf and has no applied tree of its own.
    const block = await screen.findByRole('group', { name: 'Items condition' });
    expect(block.hasAttribute('data-pending')).toBe(true);
    expect(
      block
        .querySelector('[data-slot="filter-group"]')
        ?.hasAttribute('data-pending'),
    ).toBe(false);
  });

  it('refuses to apply while a condition is wrong, and says how many', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { value: 'ten' as never });
    });
    const apply = () =>
      screen.getByRole('button', { name: /Apply/ }) as HTMLButtonElement;

    // The pill says where; this says how many, beside the button that will
    // not move until they are gone.
    expect(apply().disabled).toBe(true);
    expect(screen.getByText('1 to fix')).toBeDefined();

    act(() => filter().updateLeaf([0], { value: 10 }));

    expect(apply().disabled).toBe(false);
    expect(screen.queryByText('1 to fix')).toBeNull();
  });

  it('reads a stored leaf with a stray children property as a condition', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { children: null } as never);
    });

    // Admission and the walk read it as a leaf; so does the strip.
    expect(
      screen.getByRole('group', { name: 'Warehouse condition' }),
    ).toBeDefined();
    expect(
      document.querySelectorAll('[data-slot="filter-group"]'),
    ).toHaveLength(0);
  });

  it('lays a group conditions out in one strip, as pills', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addLeaf('status');
      filter().addLeaf('amount');
    });

    const strips = document.querySelectorAll('[data-slot="filter-conditions"]');
    expect(strips).toHaveLength(1);
    expect(
      strips[0].querySelectorAll('[data-slot="filter-condition"]'),
    ).toHaveLength(3);
    expect(
      screen.getByRole('group', { name: 'Warehouse condition' }),
    ).toBeDefined();
  });

  /**
   * D18-7: simple mode cannot write "not within this period" any other way.
   * The switch sits with the ✕; pressed, the word joins the sentence in front
   * of the operator and the tree holds the condition in a `nor` of its own —
   * which the strip still draws as this one pill, not as a group.
   */
  it('negates a condition from its pill and says so in the sentence', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const pill = screen.getByRole('group', { name: 'Warehouse condition' });
    const negate = within(pill).getByRole('button', {
      name: 'Negate the Warehouse condition',
    });
    expect(negate.getAttribute('aria-pressed')).toBe('false');
    expect(pill.querySelector('[data-slot="filter-negated"]')).toBeNull();

    fireEvent.click(negate);

    await waitFor(() =>
      expect(filter().tree.children[0]).toMatchObject({
        op: 'nor',
        children: [{ field: 'warehouse' }],
      }),
    );
    expect(filter().simple).toBe(true);
    const negated = screen.getByRole('group', { name: 'Warehouse condition' });
    expect(negated.hasAttribute('data-negated')).toBe(true);
    expect(
      negated.querySelector('[data-slot="filter-negated"]')?.textContent,
    ).toBe('not');
    expect(
      within(negated)
        .getByRole('button', { name: 'Negate the Warehouse condition' })
        .getAttribute('aria-pressed'),
    ).toBe('true');
    // Still one strip of one pill: no group block appeared around it.
    expect(
      document.querySelectorAll('[data-slot="filter-group"]'),
    ).toHaveLength(0);

    // The value and the operator still edit the condition inside.
    await choose('Warehouse operator', 'is not');
    expect(filter().tree.children[0]).toMatchObject({
      op: 'nor',
      children: [{ field: 'warehouse', operator: 'NE' }],
    });

    // ✕ takes the wrapper with the condition.
    fireEvent.click(
      within(
        screen.getByRole('group', { name: 'Warehouse condition' }),
      ).getByRole('button', { name: 'Remove Warehouse' }),
    );
    await waitFor(() => expect(filter().tree.children).toEqual([]));
  });

  it('keeps a negated field ticked in the picker, and unticks the whole of it', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));
    act(() => filter().negate([0]));

    const picker = await openPicker();
    const box = within(picker).getByRole('checkbox', { name: 'Warehouse' });
    expect(box.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(box);
    await waitFor(() => expect(filter().tree.children).toEqual([]));
  });

  /**
   * F-04: a reference field's pill searches the host's source and writes
   * `{ items: [{ id, label }] }` — the shape the kind admits — so the
   * condition passes admission and the bar can say the name later without
   * asking the source again.
   */
  it("searches a reference field's candidates and writes the pick as items", async () => {
    const user = userEvent.setup();
    const definition = {
      ...ordersDefinition(),
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'customer',
          label: 'Customer',
          kind: 'reference' as const,
          remote: 'customers',
        },
      ],
    };
    const { filter } = panel(false, definition, undefined, {}, () => ({
      search: () =>
        Promise.resolve({
          items: [{ value: 'c-1', label: 'Acme' }],
          nextCursor: null,
        }),
      resolve: () => Promise.resolve([]),
    }));
    act(() => filter().addLeaf('customer'));

    await user.click(screen.getByRole('combobox', { name: 'Customer value' }));
    await user.click(await screen.findByRole('option', { name: 'Acme' }));

    await waitFor(() =>
      expect(filter().tree.children[0]).toMatchObject({
        field: 'customer',
        value: { items: [{ id: 'c-1', label: 'Acme' }] },
      }),
    );
    expect(filter().blocked).toBe(0);
    expect(screen.getByRole('button', { name: 'Remove Acme' })).toBeDefined();
  });

  it('marks a condition blank until it says something, and invalid when wrong', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('amount'));
    const pill = () => screen.getByRole('group', { name: 'Amount condition' });

    expect(pill().hasAttribute('data-blank')).toBe(true);
    act(() => filter().updateLeaf([0], { value: 10 }));
    expect(pill().hasAttribute('data-blank')).toBe(false);
    expect(pill().hasAttribute('data-invalid')).toBe(false);
    act(() => filter().updateLeaf([0], { value: 'ten' as never }));
    expect(pill().hasAttribute('data-invalid')).toBe(true);
  });

  /**
   * The border the pill draws is nothing a screen reader reads out, so a
   * refused condition sounded like a perfectly ordinary one. The mark goes
   * on the control the code names: a value rule is the value editor's, an
   * unsupported operator is the select's, and neither is the other's.
   */
  it('marks the control an error is about, not only the pill', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('amount'));
    const value = () => screen.getByLabelText('Amount value');
    const operator = () => screen.getByLabelText('Amount operator');

    act(() => filter().updateLeaf([0], { value: 10 }));
    expect(value().getAttribute('aria-invalid')).toBeNull();
    expect(operator().getAttribute('aria-invalid')).toBeNull();

    act(() => filter().updateLeaf([0], { value: 'ten' as never }));
    expect(value().getAttribute('aria-invalid')).toBe('true');
    // The operator is the one thing about this condition that is right.
    expect(operator().getAttribute('aria-invalid')).toBeNull();
  });

  it('renders a condition that holds a tree as a block, like a group', async () => {
    const { filter } = panel(false, withItems());
    act(() => filter().addLeaf('items'));

    const block = await screen.findByRole('group', { name: 'Items condition' });
    expect(block.getAttribute('data-slot')).toBe('filter-element');
    // Nothing said inside it yet: blank, like a condition with no value.
    expect(block.hasAttribute('data-blank')).toBe(true);
    // Its own conditions strip sits inside it.
    expect(block.querySelector('[data-slot="filter-group"]')).not.toBeNull();
  });

  it('shows a tree whole, groups and their leaves included', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addGroup('or');
      filter().addLeaf('status', [1]);
    });

    // The nested condition stays visible and editable rather than dropped.
    expect(screen.getByLabelText('Warehouse value')).toBeDefined();
    expect(screen.getByLabelText('Status value')).toBeDefined();
    expect(screen.getByRole('group', { name: 'Any condition' })).toBeDefined();
  });

  /**
   * The value editor used to be named after `leaf.field`, so one row read
   * "Status condition / Status operator / status value" — an identifier on
   * screen, in a word the interface says nowhere else.
   */
  it('names every control on a row after the field, not its id', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('status'));

    const row = screen.getByRole('group', { name: 'Status condition' });
    expect(within(row).getByLabelText('Status value')).toBeDefined();
    expect(screen.queryByLabelText('status value')).toBeNull();
    expect(
      within(row).getByRole('combobox', { name: /Status operator/i }),
    ).toBeDefined();
    expect(
      within(row).getByRole('button', { name: 'Remove Status' }),
    ).toBeDefined();
  });

  it('flips a group between all and any', async () => {
    const { filter } = panel();
    act(() => filter().addGroup('and'));

    // The root keeps its own select; the nested group's is told apart by the
    // path in its name, and it is the one that flips.
    await choose('Group operator 0', 'Any condition');

    expect(filter().tree.children[0]).toMatchObject({ op: 'or' });
  });

  it('flips the root group too, not only the nested ones', async () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });

    await choose('Group operator', 'Any condition');

    expect(filter().tree.op).toBe('or');
  });

  it('offers "no condition" and writes it to the tree', async () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });

    await choose('Group operator', 'No condition');

    // Wow's third logical operator; a group is the only place a config can
    // say "none of these".
    expect(filter().tree.op).toBe('nor');
  });

  it('names every operator through the catalogue', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const options = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });

    // Every operator the field offers has an entry, so none of them can
    // render as its key.
    expect(options.textContent).not.toContain('label.operator');
    expect(filter().operatorsFor('warehouse')).toContain('NOT_IN');
  });

  /**
   * The point of naming the whole enum rather than the unreadable half of it.
   * A field's own operators used to have no key at all, so `zhCN` had nowhere
   * to put a Chinese word and the select still read `eq`.
   */
  it('shows a translated operator when the host hands over zhCN', () => {
    const { filter } = panel(false, ordersDefinition(), zhCN);
    act(() => filter().addLeaf('amount'));

    const options = screen.getByRole('combobox', {
      name: /Amount 操作符/i,
    });

    expect(options.textContent).toContain('等于');
    expect(options.textContent).not.toContain('eq');
  });

  /**
   * A condition whose value is a condition. The kernel could express it and
   * the editor could not, which is the shape of mistake this package has made
   * before — a pipeline computing something nothing renders.
   */
  it('builds a condition inside an element match', async () => {
    const { filter } = panel(false, withItems());

    act(() => filter().addLeaf('items'));

    // The row renders the same group builder the outer filter uses, over the
    // fields the entries declare rather than the view's own. Its controls
    // carry the field's name so they are not two "Group operator"s.
    await waitFor(() =>
      expect(screen.getByLabelText('Items Group operator')).toBeTruthy(),
    );
    await add(['SKU'], 'Items Add condition');

    const predicate = filter().tree.children[0] as unknown as {
      value: FilterTree;
    };
    expect(predicate.value.children[0]).toMatchObject({ field: 'items.sku' });
  });

  it('offers the entry fields, not the view fields', async () => {
    const { filter } = panel(false, withItems());

    act(() => filter().addLeaf('items'));
    const picker = await openPicker('Items Add condition');

    // `warehouse` belongs to the order, not to a line, and a predicate that
    // named it would compile into something Wow cannot answer.
    expect(within(picker).getByRole('checkbox', { name: 'SKU' })).toBeTruthy();
    expect(
      within(picker).queryByRole('checkbox', { name: 'Warehouse' }),
    ).toBeNull();
  });

  it('marks the row inside a predicate that is wrong, not the one holding it', async () => {
    const { filter } = panel(false, withItems());

    act(() => filter().addLeaf('items'));
    await add(['Qty'], 'Items Add condition');

    // A number field given text: the kind reports it under the leaf that
    // carries the predicate, and the row inside is what has to light up.
    act(() =>
      filter().updateLeaf([0], {
        value: {
          op: 'and',
          children: [{ field: 'items.qty', operator: 'EQ', value: 'x' }],
        } as never,
      }),
    );

    await waitFor(() => {
      const invalid = document.querySelectorAll('[data-invalid]');
      expect(invalid.length).toBe(1);
      expect(invalid[0].textContent).toContain('Qty');
    });
  });

  it('disables the group operator with the rest of the panel', () => {
    const { filter } = panel(true);
    act(() => filter().setMode('advanced'));

    // The panel freezes the tree while a query runs; the operator select is
    // part of the tree.
    const root = screen.getByRole('combobox', {
      name: 'Group operator',
    }) as HTMLButtonElement;
    expect(root.disabled).toBe(true);
  });

  it('adds a condition inside the group it was asked for', async () => {
    const { filter } = panel();
    act(() => filter().addGroup('or'));
    const group = screen.getByRole('group', { name: 'Any condition' });

    await add(['Warehouse'], 'Add condition', group);

    expect(filter().tree.children[0]).toMatchObject({
      op: 'or',
      children: [{ field: 'warehouse' }],
    });
  });

  it('removes a group with everything in it', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addGroup('or');
      filter().addLeaf('status', [1]);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Remove group' }));

    expect(filter().tree.children).toHaveLength(1);
    expect(filter().tree.children[0]).toMatchObject({ field: 'warehouse' });
  });

  it('shows a notice instead of rendering an over-budget tree', () => {
    // A stored tree can exceed the depth budget; the validator reports it as
    // an error, and recursing into it anyway would build as many DOM nodes
    // as the store saw fit to save.
    const deep: { op: 'and'; children: unknown[] } = {
      op: 'and',
      children: [],
    };
    let node = deep;
    for (let depth = 0; depth < 5_000; depth += 1) {
      const child = { op: 'and' as const, children: [] as unknown[] };
      node.children.push(child);
      node = child;
    }
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Deep',
      scope: 'personal',
      config: recordConfig({ filter: deep as never }),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }

    expect(() => render(<Probe />)).not.toThrow();
    const editor = latest as ReturnType<typeof useFilterEditor> | null;
    expect(
      editor?.issues.some(found => found.code === 'filter.tree.too-deep'),
    ).toBe(true);
    expect(
      screen.getByText('This filter is too large to edit here.'),
    ).toBeDefined();
  });

  it('skips the applied summary of an over-budget tree', () => {
    // Opening a saved over-wide view leaves the oversized tree as `applied`
    // too (apply is refused), and summarising it would walk every leaf and
    // render one badge per condition.
    const wide = {
      op: 'and' as const,
      children: Array.from({ length: 5_000 }, () => ({
        field: 'warehouse',
        operator: 'EQ',
        value: 'CN',
      })),
    };
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Wide',
      scope: 'personal',
      config: recordConfig({ filter: wide as never }),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }

    render(<Probe />);

    const editor = latest as ReturnType<typeof useFilterEditor> | null;
    expect(editor?.applied).toEqual([]);
    // Nor does the editor recurse into it: the notice stands in for the tree.
    expect(
      document.querySelectorAll('[data-slot="filter-condition"]').length,
    ).toBe(0);
    expect(
      screen.getByText('This filter is too large to edit here.'),
    ).toBeDefined();
  });

  it('keeps Clear as the way out of an over-budget tree', () => {
    // Nine empty groups hit the depth budget; with no leaf, Clear would be
    // the only undo, and disabling it would leave the tree stuck.
    const deep: { op: 'and'; children: unknown[] } = {
      op: 'and',
      children: [],
    };
    let node = deep;
    for (let depth = 0; depth < 12; depth += 1) {
      const child = { op: 'and' as const, children: [] as unknown[] };
      node.children.push(child);
      node = child;
    }
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Deep',
      scope: 'personal',
      config: recordConfig({ filter: deep as never }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(<Probe />);

    expect(
      (screen.getByRole('button', { name: 'Clear' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });

  /**
   * The pill read every issue at its path as "invalid", so a warning — a
   * finding that blocks nothing — painted the same red as a value the kind
   * refused. No built-in kind warns about a leaf, so one is registered here:
   * a number it will round, worth pointing out and not worth refusing.
   */
  it('marks a condition with a warning apart from one that is invalid', async () => {
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, path }) =>
        typeof value !== 'number'
          ? [{ code: 'filter.value.expected-number', severity: 'error', path }]
          : Number.isInteger(value)
            ? []
            : [{ code: 'filter.value.rounded', severity: 'warning', path }],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };
    const base = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...base,
          fields: [
            ...base.fields,
            { name: 'weight', label: 'Weight', kind: 'rounded' },
          ],
        },
      ],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }
    render(<Probe />);
    const filter = () => latest as ReturnType<typeof useFilterEditor>;
    const pill = () => screen.getByRole('group', { name: 'Weight condition' });

    act(() => filter().addLeaf('weight'));
    act(() => filter().updateLeaf([0], { value: 2.5 }));

    expect(pill().hasAttribute('data-warning')).toBe(true);
    expect(pill().hasAttribute('data-invalid')).toBe(false);
    // A warning blocks nothing: the condition applies as it stands — and the
    // summary describes the query that came back, so it waits for one.
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    act(() => filter().updateLeaf([0], { value: 'heavy' as never }));

    expect(pill().hasAttribute('data-invalid')).toBe(true);
    expect(pill().hasAttribute('data-warning')).toBe(false);
  });

  describe('the ways into a group', () => {
    it('nests a group from its own control, not from the field list', async () => {
      const harness = panel(false, ordersDefinition());
      act(() => harness.filter().setMode('advanced'));

      // Nesting left the field list when that list became a set of ticks: a
      // list of fields has no room for an entry that is not a field.
      const nest = await screen.findByRole('button', { name: 'Add a group' });
      const picker = await openPicker('Add condition');
      expect(within(picker).queryByText('Group')).toBeNull();
      fireEvent.keyDown(picker, { key: 'Escape' });

      fireEvent.click(nest);
      const menu = await screen.findByRole('menu');
      // The operator code first, then the sentence it makes of the
      // conditions under it.
      expect(menu.textContent).toContain('AND');
      expect(menu.textContent).toContain('All conditions');
      expect(menu.textContent).toContain('NOR');

      // Named in full: "OR" is also the tail of "NOR".
      fireEvent.click(
        within(menu).getByRole('menuitem', { name: 'OR Any condition' }),
      );
      await waitFor(() =>
        expect(harness.filter().tree.children).toEqual([
          { op: 'or', children: [] },
        ]),
      );
    });

    it('gives the root group one way in, not two', async () => {
      const harness = panel(false, ordersDefinition());
      act(() => harness.filter().setMode('advanced'));

      // The root is a group block in advanced mode, and a group block
      // carries its own "Add condition / Add a group". The panel's own
      // pair below it did the same two things a second time, so one screen
      // held four entries into one group.
      const root = screen.getByRole('region', { name: 'Filter' });
      // Two ways in and no more: the field picker and the nest-a-group
      // button.
      await waitFor(() =>
        expect(
          within(root).queryAllByRole('button', { name: /^Add/ }),
        ).toHaveLength(2),
      );
      expect(
        within(root).getByRole('button', { name: 'Add condition' }),
      ).toBeDefined();
      expect(
        within(root).getByRole('button', { name: 'Add a group' }),
      ).toBeDefined();
      // And the way out keeps its own place on the right of the row under
      // the tree (D12 Ⅱ), which is now all that row carries.
      const actions = document.querySelector(
        '[data-slot="filter-actions"]',
      ) as HTMLElement;
      expect(
        within(actions).queryByRole('button', { name: /^Add/ }),
      ).toBeNull();
      expect(
        within(actions).getByRole('button', { name: /Apply/ }),
      ).toBeDefined();
    });

    it("says on the panel's own toggle why simple is not on offer", async () => {
      const harness = panel(false, ordersDefinition());
      const simple = () => screen.getByRole('button', { name: 'Simple' });
      // A tree the simple editor can draw: both ways are on offer, and the
      // item points at no reason, because there is none to give.
      expect(simple().hasAttribute('aria-describedby')).toBe(false);

      act(() => harness.filter().setMode('advanced'));
      act(() => harness.filter().addGroup('or'));

      // A group the simple editor cannot draw: simple is refused, and the
      // reason is said on the item a reader reaches — through an id that
      // is there, not the draft `aria-description` one engine reads.
      await waitFor(() => expect(simple().hasAttribute('disabled')).toBe(true));
      expect(describedText(simple())).toBe(
        'These conditions need the advanced editor to be shown in full.',
      );
    });

    it('keeps the single way in where the panel is the only way in', () => {
      panel();

      // Simple mode has no group block to carry one, so the row under the
      // conditions keeps the field picker it has always had — and only it,
      // since a group it cannot draw is a group it must not offer.
      const actions = document.querySelector(
        '[data-slot="filter-actions"]',
      ) as HTMLElement;
      expect(
        within(actions).queryAllByRole('button', { name: /^Add/ }),
      ).toHaveLength(1);
      expect(
        within(actions).getByRole('button', { name: 'Add' }),
      ).toBeDefined();
    });

    it('offers no way to nest a group where groups are not shown', () => {
      panel();

      // Simple mode draws one strip of conditions, so a group it cannot
      // show is a group it must not offer to make.
      expect(screen.queryByRole('button', { name: 'Add a group' })).toBeNull();
    });

    it('says so when nothing in the catalogue matches what was typed', async () => {
      const harness = panel();
      const picker = await openPicker();

      fireEvent.change(
        within(picker).getByRole('textbox', { name: 'Search fields' }),
        {
          target: { value: 'no such field' },
        },
      );

      await waitFor(() =>
        expect(within(picker).queryAllByRole('checkbox')).toHaveLength(0),
      );
      expect(within(picker).getByText('No field matches')).toBeDefined();
      expect(harness.filter().tree.children).toEqual([]);
    });
  });
});

/**
 * The mode is a way of *editing*, not part of the filter, which is why it
 * left the panel: it is a menu on the editor's own toggle in the title bar,
 * and the panel below is the conditions and nothing else.
 */
describe('the mode the condition editor is in', () => {
  function openMixed(instance: ViewInstance) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [instance] }),
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
  }

  it('shows the effective mode when a simple config holds an advanced tree', async () => {
    openMixed(mixed);

    // The stored tree holds a group, which the simple editor cannot draw
    // faithfully, so the modes menu checks the editor in force rather than
    // the mode that was saved.
    await screen.findByRole('button', { name: 'Filter' });
    fireEvent.click(screen.getByRole('button', { name: 'Editor options' }));
    expect(
      (await screen.findByRole('menuitemradio', { name: 'Advanced' }))
        .ariaChecked,
    ).toBe('true');
  });

  it('says why simple is not on offer for such a tree', async () => {
    openMixed(mixed);
    await screen.findByRole('button', { name: 'Filter' });

    fireEvent.click(screen.getByRole('button', { name: 'Editor options' }));

    const simple = await screen.findByRole('menuitemradio', {
      name: 'Simple',
    });
    expect(simple.getAttribute('aria-disabled')).toBe('true');
    // Said on the item itself: a reader hears the option is unavailable and
    // then has nowhere else to look for why.
    expect(describedText(simple)).toBe(
      'These conditions need the advanced editor to be shown in full.',
    );
    expect(
      screen.getByRole('menuitemradio', { name: 'Advanced' }).ariaChecked,
    ).toBe('true');
  });

  it('offers both ways of editing a tree the simple editor can draw', async () => {
    openMixed(mine);
    await screen.findByRole('button', { name: 'Filter' });

    fireEvent.click(screen.getByRole('button', { name: 'Editor options' }));

    const simple = await screen.findByRole('menuitemradio', {
      name: 'Simple',
    });
    expect(simple.getAttribute('aria-disabled')).not.toBe('true');
    // Nothing refused, so nothing to explain, and no id left dangling.
    expect(simple.hasAttribute('aria-describedby')).toBe(false);
    expect(simple.ariaChecked).toBe('true');
  });
});

describe('FilterPanel and auto refresh', () => {
  /** The panel over a fresh runtime, with the runtime in reach. */
  function panelWithRuntime() {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig({
        filter: {
          op: 'and',
          children: [
            { field: 'warehouse', operator: 'EQ', value: 'CN' },
            { field: 'status', operator: 'EQ', value: 'open' },
          ],
        },
      }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <>
        <Probe />
        <button type="button">Elsewhere</button>
      </>,
    );
    const editing = () => runtime.getSnapshot().editing;
    return { runtime, editing };
  }

  it('holds the timer while an input inside has focus, and lets go after', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');

    expect(editing()).toBe(false);
    fireEvent.focus(input, { relatedTarget: null });
    expect(editing()).toBe(true);

    fireEvent.blur(input, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('does not let go while focus moves between two inputs inside', () => {
    const { runtime, editing } = panelWithRuntime();
    const setEditing = vi.spyOn(runtime, 'setEditing');
    const first = screen.getByLabelText('Warehouse value');
    const second = screen.getByLabelText('Status value');

    fireEvent.focus(first, { relatedTarget: null });
    // A move within the panel: the blur names the input gaining focus and
    // the focus names the one losing it. Neither crosses the panel's edge.
    fireEvent.blur(first, { relatedTarget: second });
    fireEvent.focus(second, { relatedTarget: first });

    expect(editing()).toBe(true);
    expect(setEditing).toHaveBeenCalledTimes(1);
    expect(setEditing).toHaveBeenCalledWith(true);
  });

  it('keeps holding while focus is in a popup of one of its controls', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');
    const trigger = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });
    fireEvent.focus(input, { relatedTarget: null });

    // A select's list renders in a portal outside the panel, so focus moving
    // into it looks like leaving. Base UI marks the trigger of an open popup
    // with `data-popup-open`, which is what the panel goes by.
    trigger.setAttribute('data-popup-open', '');
    fireEvent.blur(trigger, { relatedTarget: document.body });
    expect(editing()).toBe(true);

    // Closed again, focus back on the trigger: a later blur is a real leave.
    trigger.removeAttribute('data-popup-open');
    fireEvent.focus(trigger, { relatedTarget: document.body });
    fireEvent.blur(trigger, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('lets go when focus leaves the document altogether', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');

    fireEvent.focus(input, { relatedTarget: null });
    fireEvent.blur(input, { relatedTarget: null });

    expect(editing()).toBe(false);
  });
});

/**
 * A stored tree can hold a child that is not a node at all — another
 * release's shape, a broken write. The editor skips it: there is no field,
 * operator or value to draw a pill from. So the finding has to be read
 * somewhere, and Apply has to stay refused while it is there.
 */
describe('a stored condition the editor cannot draw', () => {
  const broken: ViewInstance = {
    ...mine,
    config: recordConfig({
      filter: {
        op: 'and',
        children: [
          { field: 'warehouse', operator: 'EQ', value: 'CN' },
          null as unknown as FilterTree,
        ],
      },
    }),
  };

  async function openBroken() {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [broken] }),
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await screen.findByRole('alert');
  }

  it('says so in the strip, where nothing else can say it', async () => {
    await openBroken();

    const strip = screen.getByRole('alert');
    // One finding, one sentence, said outright and with no fold over it
    // (F-14): the well-formed condition beside it is fine.
    expect(strip.textContent).toContain('This condition could not be read.');
    expect(strip.textContent).not.toContain('needs fixing');
    expect(within(strip).queryByRole('button', { name: '1 more' })).toBeNull();
  });

  it('refuses to apply while it is there', async () => {
    await openBroken();

    fireEvent.click(screen.getByRole('button', { name: /^Filter/ }));
    const apply = (await screen.findByRole('button', {
      name: /Apply/,
    })) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
    // And says how many, beside the button that will not move: a count that
    // left out what no pill could carry would be a button disabled for
    // nothing the user can see.
    expect(screen.getByText('1 to fix')).toBeDefined();
  });
});

describe('a filter editor with no view behind it', () => {
  it('answers every command without a runtime to run it on', () => {
    // The controller is built before a view opens, and a workbench renders
    // through that gap. Nothing it offers may throw in it.
    const { result } = renderHook(() => useFilterEditor(null));

    expect(result.current.pending).toBe(false);
    expect(() => {
      result.current.discard();
      result.current.submit();
      result.current.clear();
    }).not.toThrow();
  });
});
