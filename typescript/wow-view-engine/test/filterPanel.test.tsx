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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
} from '../src/index.js';
import type { FieldKind, ViewInstance, FilterTree } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { FilterPanel, RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { mine, setup } from './fixtures/ui.js';

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
  ): PanelHarness {
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
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
      return <FilterPanel filter={filter} disabled={disabled} />;
    }
    render(<Probe />);
    return { filter: () => latest as ReturnType<typeof useFilterEditor> };
  }

  it('lists the fields of the picker by the groups the definition declares', async () => {
    const grouped = ordersDefinition({
      fieldGroups: [
        { id: 'state', label: 'State', fields: ['status'] },
        { id: 'money', label: 'Money', fields: ['amount'] },
      ],
    });
    panel(false, grouped);

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const text = (await screen.findByRole('listbox')).textContent ?? '';

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
    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const search = await screen.findByRole('combobox', {
      name: 'Search fields',
    });

    fireEvent.change(search, { target: { value: 'sta' } });

    const names = (await screen.findAllByRole('option')).map(
      option => option.textContent,
    );
    expect(names).toEqual(['Status']);
  });

  it('offers a field once per group when adding a condition', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));

    const names = (await screen.findAllByRole('option')).map(
      item => item.textContent,
    );
    expect(names).toContain('Status');
    expect(names).not.toContain('Warehouse');
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

    // The top row is the mode switch alone; everything that acts on the tree
    // sits under the tree it acts on.
    expect(
      within(actions).getByRole('combobox', { name: 'Add' }),
    ).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: 'Clear' }),
    ).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: /Apply/ }),
    ).toBeDefined();
    const conditions = document.querySelector(
      '[data-slot="filter-conditions"]',
    ) as HTMLElement;
    expect(
      actions.compareDocumentPosition(conditions) &
        Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy();
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
    expect(screen.getByRole('combobox', { name: 'Add' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
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

  it('marks a group that was flipped since the last apply', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addGroup('or');
    });

    const group = screen.getByRole('group', { name: 'Any of' });
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
    expect(screen.getByLabelText('warehouse value')).toBeDefined();
    expect(screen.getByLabelText('status value')).toBeDefined();
    expect(screen.getByRole('group', { name: 'Any of' })).toBeDefined();
  });

  it('flips a group between all and any', () => {
    const { filter } = panel();
    act(() => filter().addGroup('and'));
    // The root stays `All of`; the toggle inside the nested group is the one
    // that flips, and both render an "Any of" button of their own.
    const toggles = document.querySelector(
      '[aria-label="Group operator 0"]',
    ) as HTMLElement;

    fireEvent.click(within(toggles).getByRole('button', { name: 'Any of' }));

    expect(filter().tree.children[0]).toMatchObject({ op: 'or' });
  });

  it('flips the root group too, not only the nested ones', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;

    fireEvent.click(within(root).getByRole('button', { name: 'Any of' }));

    expect(filter().tree.op).toBe('or');
  });

  it('offers "none of" and writes it to the tree', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;

    fireEvent.click(within(root).getByRole('button', { name: 'None of' }));

    // Wow's third logical operator; a group is the only place a config can
    // say "none of these".
    expect(filter().tree.op).toBe('nor');
  });

  it('names an operator the catalogue spells out, and derives the rest', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const options = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });

    // `EQ` reads fine derived; `NOT_IN` as "not in" does not, so it has an
    // entry. Neither should ever render as its key.
    expect(options.textContent).not.toContain('label.operator');
    expect(filter().operatorsFor('warehouse')).toContain('NOT_IN');
  });

  /**
   * A condition whose value is a condition. The kernel could express it and
   * the editor could not, which is the shape of mistake this package has made
   * before — a pipeline computing something nothing renders.
   */
  it('builds a condition inside an element match', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));

    // The row renders the same group builder the outer filter uses, over the
    // fields the entries declare rather than the view's own. Its controls
    // carry the field's name so they are not two "Group operator"s.
    await waitFor(() =>
      expect(screen.getByLabelText('Items Group operator')).toBeTruthy(),
    );
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'SKU' }));

    const predicate = filter().tree.children[0] as unknown as {
      value: FilterTree;
    };
    expect(predicate.value.children[0]).toMatchObject({ field: 'items.sku' });
  });

  it('offers the entry fields, not the view fields', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );

    // `warehouse` belongs to the order, not to a line, and a predicate that
    // named it would compile into something Wow cannot answer.
    expect(await screen.findByRole('option', { name: 'SKU' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: 'Warehouse' })).toBeNull();
  });

  it('marks the row inside a predicate that is wrong, not the one holding it', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'Qty' }));

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
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;
    // The panel freezes the tree while a query runs; the operator toggle is
    // part of the tree.
    expect(
      within(root).getByRole('button', { name: 'Any of' }).ariaDisabled,
    ).toBe('true');
  });

  it('adds a condition inside the group it was asked for', async () => {
    const { filter } = panel();
    act(() => filter().addGroup('or'));
    const group = screen.getByRole('group', { name: 'Any of' });

    fireEvent.click(
      within(group).getByRole('combobox', {
        name: 'Add in this group',
      }),
    );
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));

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

  it('shows the effective mode when a simple config holds an advanced tree', () => {
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Mixed',
      scope: 'personal',
      config: recordConfig({
        filterMode: 'simple',
        filter: {
          op: 'or',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(<Probe />);

    // The tree needs the advanced editor; the mode toggle says so rather
    // than claiming Simple over a group editor.
    expect(screen.getByRole('button', { name: 'Advanced' }).ariaPressed).toBe(
      'true',
    );
    expect(screen.getByRole('button', { name: 'Simple' }).ariaPressed).toBe(
      'false',
    );
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
      describe: ({ leaf, field }) => `${field.label} = ${String(leaf.value)}`,
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
    const input = screen.getByLabelText('warehouse value');

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
    const first = screen.getByLabelText('warehouse value');
    const second = screen.getByLabelText('status value');

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
    const input = screen.getByLabelText('warehouse value');
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
    const input = screen.getByLabelText('warehouse value');

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
      <RecordWorkbench
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
    expect(strip.textContent).toContain('needs fixing');
    fireEvent.click(within(strip).getByRole('button', { name: '1 more' }));
    expect(strip.textContent).toContain('This condition could not be read.');
    // One finding, one sentence: the well-formed condition beside it is fine.
    expect(within(strip).queryByRole('button', { name: '2 more' })).toBeNull();
  });

  it('refuses to apply while it is there', async () => {
    await openBroken();

    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
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
