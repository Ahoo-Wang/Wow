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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import {
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const shared = (count: number): ViewInstance[] =>
  Array.from({ length: count }, (_, at) => ({
    id: `ours-${at}`,
    definitionId: 'orders',
    title: `Ours ${at}`,
    scope: 'shared',
    revision: '1',
    config: recordConfig(),
  }));

function engineOver(instances: ViewInstance[], listLimit?: number) {
  return new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store: new MemoryViewStore({
      instances,
      ...(listLimit === undefined ? {} : { listLimit }),
    }),
  });
}

const CUT =
  'Only the first 2 views of each group are listed; a link to another still opens it.';

describe('a list the store cut short (R2-26)', () => {
  it('is said by the engine where an audience fills the store’s limit', async () => {
    expect((await engineOver(shared(3), 2).list('orders')).cut).toBe(2);
    // Just under it, or with no limit at all, the list is whole.
    expect((await engineOver(shared(1), 2).list('orders')).cut).toBeUndefined();
    expect((await engineOver(shared(3)).list('orders')).cut).toBeUndefined();
  });

  it('is said under the sidebar’s list and in Manage views', async () => {
    const user = userEvent.setup();
    render(
      <DataWorkbench
        engine={engineOver(shared(3), 2)}
        definitionId="orders"
        instanceId="ours-0"
      />,
    );
    await screen.findAllByRole('row');
    expect(await screen.findByText(CUT)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Manage views' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      dialog.querySelector('[data-slot="view-manager-cut"]')?.textContent,
    ).toBe(CUT);
  });

  it('says nothing of a whole list', async () => {
    render(
      <DataWorkbench
        engine={engineOver(shared(1), 2)}
        definitionId="orders"
        instanceId="ours-0"
      />,
    );
    await screen.findAllByRole('row');
    expect(screen.queryByText(CUT)).toBeNull();
  });
});
