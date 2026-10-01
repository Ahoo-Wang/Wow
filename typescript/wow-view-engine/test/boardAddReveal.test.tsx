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
 * A panel added to a full board lands below everything, and the live
 * region's 「已添加」 was all a sighted builder got (R2-38): it is scrolled
 * into view, and the keyboard the dialog handed back to 「＋ 添加」 goes on
 * to its 「⋯」.
 */

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import { DashboardWorkbench } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testSource,
  resourcesOf,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

const ownScroll = Element.prototype.scrollIntoView;

afterEach(() => {
  cleanup();
  Element.prototype.scrollIntoView = ownScroll;
});

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
};

function open() {
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store: new MemoryViewStore({
      instances: [
        pending,
        byWarehouse,
        {
          id: 'overview-1',
          definitionId: 'overview',
          title: 'Operations',
          scope: 'personal',
          revision: '1',
          config: dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
        },
      ],
    }),
  });
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="overview-1"
    />,
  );
  return userEvent.setup({ pointerEventsCheck: 0 });
}

describe('a panel just added', () => {
  it('is scrolled into view, and the keyboard goes on to its 「⋯」', async () => {
    const scrolled: Element[] = [];
    // jsdom lays nothing out and has no `scrollIntoView` of its own.
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this);
    };
    const user = open();
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    await screen.findByRole('region', { name: 'Editing' });
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Saved view…' }),
    );
    await user.click(
      await screen.findByRole('button', { name: /By warehouse/ }),
    );

    const menu = await screen.findByRole('button', {
      name: 'Actions for “By warehouse”',
    });
    await waitFor(() => expect(document.activeElement).toBe(menu));
    const added = menu.closest('[data-panel-id]');
    expect(added).not.toBeNull();
    expect(scrolled).toContain(added);
  });
});
