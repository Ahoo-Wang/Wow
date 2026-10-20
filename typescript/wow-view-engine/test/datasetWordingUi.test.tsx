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
 * The view manager's wording tab (D91): where the host grants
 * `editDatasetWording` and the dataset was registered with a store of
 * wording, an administrator renames the dataset and its fields, hides one,
 * moves one, and saves; the dataset registers again in the new words.
 */

import type { QueryDescriptorResult } from '../src/index.js';
import { QueryModels, type QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DatasetResources,
  type DatasetOverrides,
  type DatasetWordingStore,
  type DataViewDefinition,
  MemoryViewStore,
  ViewEngine,
  type ViewPermissions,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { testSource } from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

afterEach(cleanup);

const ID = 'analytics.order_wide';

function descriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    model: QueryModels.DATASET,
    version: 'sha256:1',
    record: { ...base.record, identity: 'order_id', rootOperators: [] },
    fields: [
      describedField('order_id'),
      describedField('city'),
      describedField('channel'),
    ],
    elements: [],
  };
}

function sourceOf(described: QueryModelDescriptor) {
  const result: QueryDescriptorResult = {
    notModified: false,
    descriptor: described,
    version: described.version,
  };
  return testSource({ describe: vi.fn(() => Promise.resolve(result)) });
}

function permitting(editDatasetWording?: boolean) {
  return (): ViewPermissions => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    ...(editDatasetWording === undefined ? {} : { editDatasetWording }),
    instance: () => ({ save: true, rename: true, delete: true }),
  });
}

async function setup(editDatasetWording?: boolean) {
  const engine = new ViewEngine({
    resources: [],
    store: new MemoryViewStore({ permissions: permitting(editDatasetWording) }),
  });
  const set = vi.fn(
    (_dataset: string, _wording: DatasetOverrides, revision: string) =>
      Promise.resolve(String(Number(revision) + 1)),
  );
  const wordings: DatasetWordingStore = {
    list: () => Promise.resolve(new Map()),
    set,
  };
  await new DatasetResources(engine, {
    list: () =>
      Promise.resolve([
        {
          contextName: 'analytics',
          name: 'order_wide',
          title: 'Orders',
          version: 'sha256:1',
        },
      ]),
    source: () => sourceOf(descriptor()),
    wordings,
  }).refresh();
  render(<DataWorkbench engine={engine} definitionId={ID} />);
  return { engine, set };
}

describe('the wording tab of the view manager (D91)', () => {
  it('renames, hides and moves fields, and saves the wording whole', async () => {
    const { engine, set } = await setup(true);
    fireEvent.click(
      await screen.findByRole('button', { name: 'Manage views' }),
    );
    const manager = await screen.findByRole('dialog');
    fireEvent.click(within(manager).getByRole('tab', { name: 'Wording' }));

    const title = within(manager).getByLabelText('Title');
    expect(title).toHaveProperty('placeholder', 'Orders');
    fireEvent.change(title, { target: { value: '订单' } });
    fireEvent.change(within(manager).getByLabelText('Name of city'), {
      target: { value: '城市' },
    });
    fireEvent.click(
      within(manager).getByRole('checkbox', { name: 'Show channel' }),
    );
    fireEvent.click(
      within(manager).getByRole('button', { name: 'Move city up' }),
    );
    fireEvent.click(
      within(manager).getByRole('button', { name: 'Save wording' }),
    );

    await waitFor(() =>
      expect(within(manager).getByRole('status').textContent).toBe(
        'Saved. Views opened from now on read the new words.',
      ),
    );
    expect(set).toHaveBeenCalledWith(
      ID,
      {
        title: '订单',
        fields: { city: '城市', order_id: {}, channel: false },
      },
      '0',
      expect.objectContaining({ requestId: expect.any(String) }),
    );
    const definition = engine.definitions.get(ID) as DataViewDefinition;
    expect(definition.title).toBe('订单');
    expect(definition.fields.map(field => field.name)).toEqual([
      'city',
      'order_id',
    ]);
    expect(engine.wordingOf(ID)?.revision).toBe('1');
  });

  it('says so when the store refuses the wording', async () => {
    const { set } = await setup(true);
    set.mockRejectedValueOnce(new Error('stale'));
    fireEvent.click(
      await screen.findByRole('button', { name: 'Manage views' }),
    );
    const manager = await screen.findByRole('dialog');
    fireEvent.click(within(manager).getByRole('tab', { name: 'Wording' }));
    fireEvent.click(
      within(manager).getByRole('button', { name: 'Save wording' }),
    );

    await waitFor(() =>
      expect(within(manager).getByRole('status').textContent).toBe(
        'The wording could not be saved. Try again.',
      ),
    );
  });

  it('offers no wording tab without editDatasetWording', async () => {
    await setup();
    fireEvent.click(
      await screen.findByRole('button', { name: 'Manage views' }),
    );
    const manager = await screen.findByRole('dialog');
    expect(within(manager).queryByRole('tab', { name: 'Wording' })).toBeNull();
  });
});
