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
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type ViewInstance,
} from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { resourcesOf } from './fixtures.js';
import {
  DEALS,
  dealsDefinition,
  dealsKanban,
  kanbanConfig,
} from './fixtures/kanban.js';

afterEach(cleanup);

function open(
  instance: ViewInstance = dealsKanban,
  documents: readonly RecordData[] = DEALS,
) {
  const store = new MemoryViewStore({ instances: [instance] });
  const engine = new ViewEngine({
    resources: resourcesOf([dealsDefinition()], () => memorySource(documents)),
    store,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="deals"
      instanceId={instance.id}
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
    fireEvent.click(within(lead).getByRole('button', { name: 'Load more' }));
    await waitFor(() =>
      expect(cardTitles(lead)).toEqual(['d-1', 'd-2', 'd-3']),
    );
    expect(
      within(lead).queryByRole('button', { name: 'Load more' }),
    ).toBeNull();
    // The other lanes never moved.
    expect(cardTitles(await lane('Won, 1 records'))).toEqual(['d-4']);
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
