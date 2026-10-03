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
 * What the server-render and hydration suites render: one engine over a
 * `MemoryViewStore`, a record view and a board declared in code, and each
 * surface a host would put on a server-rendered page, under `ViewHost`.
 */

import type { ReactElement } from 'react';
import {
  MemoryViewStore,
  systemInstanceId,
  ViewEngine,
} from '../../src/index.js';
import {
  DashboardWorkbench,
  DataWorkbench,
  EmbeddedDashboard,
  EmbeddedView,
  ViewHost,
} from '../../src/ui/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from '../fixtures.js';
import { panel } from './dashboard.js';

export const ORDERS = systemInstanceId('orders', 'all');
export const BOARD = systemInstanceId('overview', 'main');

/** An engine over a memory store, every view it opens declared in code. */
export function serverEngine() {
  const source = testSource();
  const store = new MemoryViewStore();
  const engine = new ViewEngine({
    resources: [
      {
        definition: ordersDefinition({
          views: [{ id: 'all', title: 'All orders', config: recordConfig() }],
        }),
        source,
      },
      {
        definition: overviewDefinition({
          views: [
            {
              id: 'main',
              title: 'Main board',
              config: dashboardConfig({
                panels: [panel({ instanceId: ORDERS })],
              }),
            },
          ],
        }),
      },
    ],
    store,
    onIssue: () => {},
  });
  return { engine, source, store };
}

/**
 * Each surface that opens a view, as a page puts it under `ViewHost`, and
 * what it says while its view opens — the state a server renders, since a
 * view opens in an effect and no server runs one. The workbenches are
 * addressed at a view, so they draw the opening skeleton (`OpeningSkeleton`)
 * beside the view list's own; the embeds draw theirs.
 */
export const SURFACES: Record<
  string,
  {
    page: (engine: ViewEngine) => ReactElement;
    opening: string;
    /** The slot that stands in for the view while it opens. */
    slot: string;
  }
> = {
  DataWorkbench: {
    page: engine => (
      <ViewHost engine={engine}>
        <DataWorkbench definitionId="orders" instanceId={ORDERS} />
      </ViewHost>
    ),
    opening: 'Opening the view',
    slot: 'opening-skeleton',
  },
  DashboardWorkbench: {
    page: engine => (
      <ViewHost engine={engine}>
        <DashboardWorkbench definitionId="overview" instanceId={BOARD} />
      </ViewHost>
    ),
    opening: 'Opening the dashboard',
    slot: 'opening-skeleton',
  },
  EmbeddedView: {
    page: engine => (
      <ViewHost engine={engine}>
        <EmbeddedView instanceId={ORDERS} withTitle />
      </ViewHost>
    ),
    opening: 'Opening the view',
    slot: 'embed-opening',
  },
  EmbeddedDashboard: {
    page: engine => (
      <ViewHost engine={engine}>
        <EmbeddedDashboard instanceId={BOARD} />
      </ViewHost>
    ),
    opening: 'Opening the dashboard',
    slot: 'embed-opening',
  },
};
