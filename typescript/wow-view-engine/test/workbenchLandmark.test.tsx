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
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import {
  DashboardWorkbench,
  DataWorkbench,
  type WorkbenchLandmark,
} from '../src/ui/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

/**
 * The landmark the work column is (Q64): `main` by default, a named
 * `region` where the host's page has a `main` of its own. Either way the
 * column wears the slot the layout reads it by, so the stylesheet never
 * depends on the tag (the browser story measures that the pixels agree).
 */
afterEach(cleanup);

const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending',
  scope: 'shared',
  revision: '1',
  config: recordConfig(),
};

const board: ViewInstance = {
  id: 'board',
  definitionId: 'overview',
  title: 'Board',
  scope: 'shared',
  revision: '1',
  config: dashboardConfig(),
};

function engine(): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store: new MemoryViewStore({ instances: [pending, board] }),
    resolveSource: () => testSource(),
  });
}

function column(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-slot="workbench-main"]')!;
}

describe('the data workbench', () => {
  function open(landmark?: WorkbenchLandmark, instanceId = 'pending') {
    render(
      <DataWorkbench
        engine={engine()}
        definitionId="orders"
        instanceId={instanceId}
        landmark={landmark}
      />,
    );
  }

  it('is the main landmark by default, named by the open view', async () => {
    open();

    const main = await screen.findByRole('main', { name: 'Pending' });
    expect(main.tagName).toBe('MAIN');
    expect(main).toBe(column());
    expect(screen.queryByRole('region', { name: 'Pending' })).toBeNull();
  });

  it('is a region named by the open view when the host asks for one', async () => {
    open('region');

    const region = await screen.findByRole('region', { name: 'Pending' });
    expect(region.tagName).toBe('SECTION');
    expect(region).toBe(column());
    expect(screen.queryByRole('main')).toBeNull();
  });

  /**
   * A section is a region only while it has a name. With no view open there
   * is no title to point at, so the definition's names it.
   */
  it('names the region by the definition while no view is open', async () => {
    open('region', 'no-such-view');
    await waitFor(() =>
      expect(document.querySelector('[role="alert"]')).toBeTruthy(),
    );

    const region = screen.getByRole('region', { name: 'Orders' });
    expect(region).toBe(column());
    expect(region.hasAttribute('aria-labelledby')).toBe(false);
  });

  it('leaves a main without a view unnamed, as before', async () => {
    open('main', 'no-such-view');
    await waitFor(() =>
      expect(document.querySelector('[role="alert"]')).toBeTruthy(),
    );

    const main = screen.getByRole('main');
    expect(main.hasAttribute('aria-label')).toBe(false);
    expect(main.hasAttribute('aria-labelledby')).toBe(false);
  });
});

describe('the dashboard workbench', () => {
  it.each<[WorkbenchLandmark | undefined, string, string]>([
    [undefined, 'main', 'MAIN'],
    ['region', 'region', 'SECTION'],
  ])('with landmark %s is a %s', async (landmark, role, tag) => {
    render(
      <DashboardWorkbench
        engine={engine()}
        definitionId="overview"
        instanceId="board"
        landmark={landmark}
      />,
    );

    const found = await screen.findByRole(role, { name: 'Board' });
    expect(found.tagName).toBe(tag);
    expect(found).toBe(column());
    expect(document.querySelectorAll('main')).toHaveLength(
      role === 'main' ? 1 : 0,
    );
  });
});
