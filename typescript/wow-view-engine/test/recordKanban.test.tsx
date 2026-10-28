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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  actions,
  type RecordActions,
  type RecordData,
  type ViewInstance,
} from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import { DataWorkbench, EmbeddedView } from '../src/ui/index.js';
import {
  RecordPanel,
  RecordPanelPaging,
} from '../src/ui/dashboard/PanelBodies.js';
import { ViewSurface } from '../src/ui/kit/ViewSurface.js';
import type { RecordViewRuntime } from '../src/index.js';
import { resourcesOf } from './fixtures.js';
import {
  DEALS,
  dealsDefinition,
  dealsKanban,
  kanbanConfig,
} from './fixtures/kanban.js';
import { AggregationFunction } from '@ahoo-wang/wow-client/dsl';

afterEach(cleanup);

function open(
  instance: ViewInstance = dealsKanban,
  documents: readonly RecordData[] = DEALS,
  declared?: RecordActions,
  definition = dealsDefinition(),
) {
  const store = new MemoryViewStore({ instances: [instance] });
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => memorySource(documents)),
    store,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="deals"
      instanceId={instance.id}
      {...(declared ? { record: { actions: declared } } : {})}
    />,
  );
  return { store };
}

const lane = (name: RegExp | string) => screen.findByRole('region', { name });

function cardTitles(region: HTMLElement): string[] {
  return within(region)
    .queryAllByText(/^d-\d$/, {
      selector:
        '[data-slot="card-title-text"] *, [data-slot="card-title-text"]',
    })
    .map(node => node.textContent ?? '');
}

describe('RecordKanban in the workbench', () => {
  it('draws one named lane per stage, its count and its cards', async () => {
    open();
    const lead = await lane('Lead, 3 records');
    expect(within(lead).getByText('3')).toBeTruthy();
    expect(cardTitles(lead)).toEqual(['d-1', 'd-2']);
    const won = await lane('Won, 1 records');
    expect(within(won).getByText('Won').closest('[data-tone]')).toHaveProperty(
      'dataset.tone',
      'success',
    );
    // An empty stage is drawn, and says so.
    const lost = await lane('Lost, 0 records');
    expect(within(lost).getByText('No records')).toBeTruthy();
    // Not a stage: what the options do not list, each card it has loaded.
    expect(cardTitles(await lane('Other, 2 shown'))).toEqual(['d-5', 'd-6']);
    // A kanban's lanes page on their own: no pager under them.
    expect(screen.queryByRole('navigation', { name: /pag/i })).toBeNull();
  });

  it('says the one empty result when no lane holds a card', async () => {
    open(dealsKanban, []);
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="empty-title"]'),
      ).not.toBeNull(),
    );
    expect(document.querySelector('[data-slot="record-kanban"]')).toBeNull();
  });

  it('draws no board for a kanban that will not run, and says why above it', async () => {
    open({
      ...dealsKanban,
      config: kanbanConfig({ kanban: { lane: 'amount' } }),
    });
    expect(
      await screen.findByText(/has no options to lay lanes out by/),
    ).toBeTruthy();
    expect(document.querySelector('[data-slot="record-kanban"]')).toBeNull();
  });

  it('draws no「其他」lane while every deal has a stage the options list', async () => {
    open(
      dealsKanban,
      DEALS.filter(deal => deal.id !== 'd-5' && deal.id !== 'd-6'),
    );
    await lane('Lead, 3 records');
    expect(screen.queryByRole('region', { name: /^Other/ })).toBeNull();
  });

  it("loads a lane's next cards under the ones it has", async () => {
    open();
    const lead = await lane('Lead, 3 records');
    // Counted, so it says how many are still to come.
    fireEvent.click(
      within(lead).getByRole('button', { name: 'Load more (1 left)' }),
    );
    await waitFor(() =>
      expect(cardTitles(lead)).toEqual(['d-1', 'd-2', 'd-3']),
    );
    expect(
      within(lead).queryByRole('button', { name: /^Load more/ }),
    ).toBeNull();
    // The other lanes never moved.
    expect(cardTitles(await lane('Won, 1 records'))).toEqual(['d-4']);
  });

  it('loads the next cards when a lane is scrolled to its end', async () => {
    open();
    const lead = await lane('Lead, 3 records');
    const port = lead.querySelector<HTMLElement>(
      '[data-slot="kanban-lane-cards"]',
    )!;
    // jsdom lays nothing out: the port says it is scrolled to its foot.
    Object.defineProperties(port, {
      scrollHeight: { configurable: true, value: 400 },
      clientHeight: { configurable: true, value: 300 },
      scrollTop: { configurable: true, value: 80 },
    });
    fireEvent.scroll(port);
    await waitFor(() =>
      expect(cardTitles(lead)).toEqual(['d-1', 'd-2', 'd-3']),
    );
  });

  it('moves a card to the stage its menu picks, through the declared move', async () => {
    const documents = DEALS.map(deal => ({ ...deal }));
    const declared = actions([
      {
        id: 'move',
        label: 'Change stage',
        moves: 'stage',
        form: {
          stage: {
            label: 'Stage',
            options: [
              { value: 'LEAD', label: 'Lead' },
              { value: 'WON', label: 'Won' },
              { value: 'LOST', label: 'Lost' },
            ],
          },
        },
        available: (row, { input }) =>
          input?.stage === row.data.stage ? 'Already there' : true,
        run: async (row, input) => {
          const found = documents.find(deal => deal.id === row.key);
          if (found) found.stage = String(input.stage);
        },
      },
    ]);
    open(dealsKanban, documents, declared);
    const lead = await lane('Lead, 3 records');
    await userEvent.click(
      within(lead).getByRole('button', { name: 'Actions for d-1' }),
    );
    const menuEl = await screen.findByRole('menu');
    await userEvent.click(
      within(menuEl).getByRole('menuitem', {
        name: 'Lost',
      }),
    );
    expect(cardTitles(await lane('Lost, 1 records'))).toEqual(['d-1']);
    expect(await lane('Lead, 2 records')).toBeTruthy();
  });

  it('folds a lane away from the kanban settings, and asks again', async () => {
    open();
    await lane('Won, 1 records');
    fireEvent.click(screen.getByRole('button', { name: 'Kanban settings' }));
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Won' }));
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: /^Won/ })).toBeNull(),
    );
    expect(await lane('Lead, 3 records')).toBeTruthy();
  });

  it('heads a lane with its summaries, what each is beside its number', async () => {
    open({
      ...dealsKanban,
      config: kanbanConfig({
        pageSize: 2,
        summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
      }),
    });
    const lead = await lane('Lead, 3 records');
    const line = within(lead).getByRole('term').parentElement!;
    expect(within(line).getByRole('term').textContent).toBe('Sum of Amount');
    expect(within(line).getByRole('definition').textContent).toBe('60');
  });

  it('waits in the lanes it will draw, before a first answer', async () => {
    const memory = memorySource(DEALS);
    const engine = new ViewEngine({
      resources: resourcesOf([dealsDefinition()], () => ({
        ...memory,
        cursor: () => new Promise(() => {}),
      })),
      store: new MemoryViewStore({ instances: [dealsKanban] }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="deals"
        instanceId={dealsKanban.id}
      />,
    );
    const skeleton = await waitFor(() => {
      const found = document.querySelector(
        '[data-slot="record-kanban-skeleton"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(
      [...skeleton.querySelectorAll('[data-slot="kanban-lane-title"]')].map(
        title => title.textContent,
      ),
    ).toEqual(['Lead', 'Won', 'Lost']);
  });

  it('switches a table into the kanban, its lanes written in', async () => {
    open({
      ...dealsKanban,
      config: kanbanConfig({ layout: 'table', kanban: undefined, pageSize: 2 }),
    });
    await screen.findAllByRole('row');
    fireEvent.click(screen.getByRole('button', { name: 'Kanban' }));
    expect(await lane('Lead, 3 records')).toBeTruthy();
  });
});

describe('the kanban settings', () => {
  it('puts a folded lane back, and lays the lanes out by another field', async () => {
    const definition = dealsDefinition({
      fields: [
        ...dealsDefinition().fields,
        {
          name: 'priority',
          label: 'Priority',
          kind: 'enum',
          options: [
            { value: 'P1', label: 'Urgent' },
            { value: 'P2', label: 'Normal' },
          ],
        },
      ],
    });
    open(dealsKanban, DEALS, undefined, definition);
    await lane('Won, 1 records');
    await userEvent.click(
      screen.getByRole('button', { name: 'Kanban settings' }),
    );
    const won = await screen.findByRole('checkbox', { name: 'Won' });
    await userEvent.click(won);
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: /^Won/ })).toBeNull(),
    );
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Won' }));
    expect(await lane('Won, 1 records')).toBeTruthy();

    await userEvent.click(
      await screen.findByRole('combobox', { name: 'Lanes by' }),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: 'Priority' }),
    );
    // No deal has a priority yet: two empty lanes, every deal in「其他」,
    // two at a time.
    expect(await lane('Urgent, 0 records')).toBeTruthy();
    expect(await lane('Normal, 0 records')).toBeTruthy();
    expect(await lane('Other, 2 shown')).toBeTruthy();
  });
});

describe('a kanban outside the workbench', () => {
  function engineOf() {
    return new ViewEngine({
      resources: resourcesOf([dealsDefinition()], () => memorySource(DEALS)),
      store: new MemoryViewStore({ instances: [dealsKanban] }),
    });
  }

  it('is laid out in lanes in an embedded view, with no pager', async () => {
    render(<EmbeddedView engine={engineOf()} instanceId={dealsKanban.id} />);
    expect(await lane('Lead, 3 records')).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it("is laid out in lanes in a board's record panel, whose pages it keeps to its lanes", async () => {
    const runtime = (await engineOf().open(
      dealsKanban.id,
    )) as RecordViewRuntime;
    const { container } = render(
      <ViewSurface>
        <RecordPanel runtime={runtime} name="Pipeline" />
        <RecordPanelPaging runtime={runtime} name="Pipeline" />
      </ViewSurface>,
    );
    expect(await lane('Lead, 3 records')).toBeTruthy();
    expect(
      container.querySelector('[data-slot="record-pagination"]'),
    ).toBeNull();
    runtime.dispose();
  });
});
