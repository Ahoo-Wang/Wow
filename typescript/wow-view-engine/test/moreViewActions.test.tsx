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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { mine, ordersDefinition, resourcesOf, testSource } from './fixtures.js';

afterEach(cleanup);

async function open() {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store,
  });
  const user = userEvent.setup();
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
    />,
  );
  await screen.findAllByRole('row');
  return { store, user };
}

const more = () => screen.getByRole('button', { name: 'More view actions' });

describe('the title bar’s more menu (R2-98)', () => {
  it('moves the open view to the other audience, and says so', async () => {
    const { store, user } = await open();

    await user.click(more());
    await user.click(
      await screen.findByRole('menuitem', { name: 'Make shared' }),
    );

    await waitFor(async () =>
      expect((await store.get('orders-1')).scope).toBe('shared'),
    );
    expect(await screen.findByText('Mine is now shared')).toBeTruthy();
    // And back, from the same menu, now named the other way.
    await user.click(more());
    await user.click(
      await screen.findByRole('menuitem', { name: 'Make personal' }),
    );
    await waitFor(async () =>
      expect((await store.get('orders-1')).scope).toBe('personal'),
    );
  });

  it('offers no link where no router puts the view in the address', async () => {
    const { user } = await open();
    await user.click(more());
    await screen.findByRole('menuitem', { name: 'Make shared' });
    expect(screen.queryByRole('menuitem', { name: 'Copy link' })).toBeNull();
  });
});
