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
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewSource } from '../src/index.js';
import {
  cellText,
  cellValue,
  displayValue,
  DataWorkbench,
  useSurfaceDisplay,
  useViewMessages,
} from '../src/ui/index.js';
import type { RecordCell, DataWorkbenchProps } from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The workbench with a host holding which view is open. «一键重开» in a
 * browser is a link, so the two directions have to exist on the component and
 * not only in the controller: a route opens a view, and the view the user
 * picks goes back into the route.
 */
describe('a DataWorkbench a host routes', () => {
  const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };

  function routed(props: Partial<DataWorkbenchProps> = {}) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine, other] }),
      resolveSource: () => testSource(),
    });
    const draw = (overrides: Partial<DataWorkbenchProps>) => (
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        {...props}
        {...overrides}
      />
    );
    const view = render(draw({}));
    return {
      engine,
      route: (id: string | null) => view.rerender(draw({ instanceId: id })),
    };
  }

  /** The current view is the one the sidebar marks. */
  function current(): string | null {
    return (
      screen
        .getAllByRole('button')
        .find(button => button.ariaCurrent === 'true')?.textContent ?? null
    );
  }

  it('opens the view the host routed to', async () => {
    const { route } = routed();
    await waitFor(() => expect(current()).toBe('Mine'));

    route('orders-2');

    await waitFor(() => expect(current()).toBe('Other'));
  });

  it('opens the effective default when the route names none', async () => {
    const { route } = routed({ instanceId: 'orders-2' });
    await waitFor(() => expect(current()).toBe('Other'));

    route(null);

    // The definition's own view, which wears its audience beside its name.
    await waitFor(() => expect(current()).toContain('All orders'));
  });

  it('reports the view the user picked, so the route can follow', async () => {
    const told = vi.fn();
    routed({ onInstanceChange: told });
    await waitFor(() => expect(current()).toBe('Mine'));

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-2'));
    await waitFor(() => expect(current()).toBe('Other'));
  });

  /**
   * A route change is a switch, so it is asked about — and a "stay" leaves
   * the host's route naming a view that is not on screen, which is why the
   * workbench then says which one is.
   */
  it('asks before a routed switch loses a draft, and says what stayed', async () => {
    const told = vi.fn();
    const { route } = routed({ onInstanceChange: told });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.keyDown(document.body, { key: 'Escape' });

    route('orders-2');
    const asked = await screen.findByRole('alertdialog');
    expect(told).not.toHaveBeenCalled();

    fireEvent.click(within(asked).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-1'));
    expect(current()).toBe('Mine');
  });
});

/**
 * What a host may change about the result without writing the workbench
 * itself. One cell nobody else could draw is the commonest reason to walk
 * away from a default component, and the way back is that the package's own
 * reading of a value is exported: override the column you mean, and fall
 * back for the rest.
 */
describe('a DataWorkbench a host draws cells in', () => {
  function workbench(
    props: Partial<DataWorkbenchProps> = {},
    source: ViewSource = testSource(),
  ) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        {...props}
      />,
    );
  }

  /** A host's renderer: its own amount cell, the package's reading for the rest. */
  function HostCell({ cell }: { cell: RecordCell }) {
    const messages = useViewMessages();
    const display = useSurfaceDisplay();
    if (cell.column.field !== 'amount')
      return cellValue(cell.value, cell.column, messages, display, 'table');
    return <span data-testid="lamp">{`${cell.value} ●`}</span>;
  }

  it('draws the cells the host renders, and reads the rest itself', async () => {
    workbench({ record: { renderCell: cell => <HostCell cell={cell} /> } });

    const lamps = await screen.findAllByTestId('lamp');
    expect(lamps.map(lamp => lamp.textContent)).toEqual(['10 ●', '20 ●']);
    // Everything the host said nothing about still reads as it always did:
    // `cellValue` is the fallback, not a stub.
    expect(screen.getAllByRole('cell').map(cell => cell.textContent)).toContain(
      'o-1',
    );
  });

  it('draws the cards the host renders', async () => {
    const cards: ViewInstance = {
      ...mine,
      config: recordConfig({ layout: 'card' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [cards] }),
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{
          renderCell: cell => (
            <span data-testid="plain">{String(cell.value)}</span>
          ),
        }}
      />,
    );

    await waitFor(() =>
      expect(screen.getAllByTestId('plain').length).toBeGreaterThan(0),
    );
  });

  /**
   * A surface with nothing to do with a selection shows no checkboxes — in
   * either layout, because a card is a row folded out and switching layout
   * must not hand the choice back.
   */
  it('takes the selection away when the host offers nothing to do with it', async () => {
    workbench({ record: { selectable: false } });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(screen.queryByRole('checkbox', { name: /select/i })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));

    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="record-cards"]'),
      ).not.toBeNull(),
    );
    expect(screen.queryByRole('checkbox', { name: /select/i })).toBeNull();
  });

  it("says the host's own words when there is nothing to show", async () => {
    workbench(
      {
        record: {
          emptyTitle: 'Nothing is waiting to ship',
          emptyDescription: 'Every order has left the warehouse.',
        },
      },
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
      }),
    );

    // Drawn as the title, and said by the result's live region in the very
    // same words — two nodes hold it on purpose, so this addresses the one
    // on screen.
    expect(
      await screen.findByText('Nothing is waiting to ship', {
        selector: '[data-slot="empty-title"]',
      }),
    ).toBeDefined();
    expect(
      screen.getByText('Every order has left the warehouse.'),
    ).toBeDefined();
  });

  /**
   * All three come off the package entry, not out of a deep path: a host
   * handed `renderCell` and no way to reach the reading it falls back to has
   * been handed a choice between its own cell and every other cell.
   */
  it('exports the whole reading from the entry, not only the node one', () => {
    expect(typeof cellValue).toBe('function');
    expect(typeof cellText).toBe('function');
    const shown = displayValue(
      INSTANT,
      { kind: 'datetime' },
      { locale: 'en-US', timeZone: ZONE },
    );
    // The same Intl call, not a literal: what is asserted is the zone and
    // the language it reads in, not the ICU data a Node release ships.
    expect(shown).toBe(
      new Intl.DateTimeFormat('en-US', {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: ZONE,
      }).format(INSTANT),
    );
  });
});
