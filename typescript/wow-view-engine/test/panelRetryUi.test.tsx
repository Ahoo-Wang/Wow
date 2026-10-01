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
 * A panel whose view could not be read just now says so — not that the view
 * was deleted or not shared — and offers to try again; one that is gone
 * offers nothing a press could change (`PanelUnavailable`).
 */

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type DashboardPanel,
} from '../src/index.js';
import { EmbeddedDashboard } from '../src/ui/index.js';
import { defaultMessages } from '../src/ui/kit/messages.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
  resourcesOf,
} from './fixtures.js';

afterEach(cleanup);

const list = {
  id: 'list',
  kind: 'view',
  title: 'Order list',
  instanceId: 'list',
  bindings: [],
  layout: { x: 0, y: 0, w: 12, h: 4 },
} as DashboardPanel;

function engineFailing(error: unknown) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'list',
        definitionId: 'orders',
        title: 'Order list',
        scope: 'shared',
        revision: 'r1',
        config: recordConfig(),
      },
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'shared',
        revision: 'r1',
        config: dashboardConfig({ panels: [list] }),
      },
    ],
  });
  const get = store.get.bind(store);
  vi.spyOn(store, 'get').mockImplementation(id =>
    id === 'list' ? Promise.reject(error) : get(id),
  );
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store,
  });
  /** The store answers from now on. */
  const heal = () => vi.mocked(store.get).mockImplementation(get);
  return { engine, heal };
}

describe('a panel whose view could not be read', () => {
  it('says it could not be opened and tries again on a press', async () => {
    const { engine, heal } = engineFailing(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    const user = userEvent.setup();
    render(
      <EmbeddedDashboard
        engine={engine}
        instanceId="board"
        interaction="interactive"
      />,
    );

    const again = await screen.findByRole('button', {
      name: defaultMessages['label.panel.retry'],
    });
    expect(
      screen.getByText(defaultMessages['label.panel.out.failed']),
    ).toBeDefined();
    expect(
      screen.getByText(defaultMessages['label.panel.way-out.retry']),
    ).toBeDefined();
    expect(
      screen.queryByText(defaultMessages['label.panel.out.missing']),
    ).toBeNull();

    heal();
    await user.click(again);
    await waitFor(() =>
      expect(screen.getAllByRole('row').length).toBeGreaterThan(1),
    );
    expect(
      screen.queryByText(defaultMessages['label.panel.out.failed']),
    ).toBeNull();
  });

  it('offers no retry for a view that is gone', async () => {
    const { engine } = engineFailing(new ViewStoreError('NOT_FOUND', 'gone'));
    render(
      <EmbeddedDashboard
        engine={engine}
        instanceId="board"
        interaction="interactive"
      />,
    );

    await screen.findByText(defaultMessages['label.panel.out.missing']);
    expect(
      screen.queryByRole('button', {
        name: defaultMessages['label.panel.retry'],
      }),
    ).toBeNull();
  });
});
