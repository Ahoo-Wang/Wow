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

import { fetcher, type Fetcher } from '@ahoo-wang/fetcher';
import {
  DatasetDirectoryClient,
  DatasetQueryClient,
} from '@ahoo-wang/wow-client';
import {
  DatasetResources,
  ViewEngine,
  type DashboardDefinition,
  type ViewStore,
} from '@ahoo-wang/wow-view-engine';
import { browserRuntimeEnvironment } from '@ahoo-wang/wow-view-engine/react';
import { WowViewStore } from '@ahoo-wang/wow-view-store';

/** The boards: a board owns no data, its panels read the datasets. */
export const BOARDS: DashboardDefinition = {
  id: 'boards',
  title: '看板',
  kind: 'dashboard',
};

/** An engine over the boards; the datasets are registered as served. */
export function createAnalyticsEngine(store: ViewStore): ViewEngine {
  return new ViewEngine({
    store,
    resources: [{ definition: BOARDS }],
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
    // A production build drops the findings otherwise: a dataset whose
    // definition fails admission would say only that it has problems.
    onIssue: issue => console.warn('[view-engine]', issue.code, issue),
  });
}

/**
 * The datasets the analytics server publishes, followed through its
 * directory (D85): `refresh` registers new and changed ones and removes
 * retired ones.
 */
export function datasetResources(
  engine: ViewEngine,
  target: Fetcher = fetcher,
): DatasetResources {
  const directory = new DatasetDirectoryClient({
    basePath: '',
    fetcher: target,
  });
  return new DatasetResources(
    engine,
    {
      list: () => directory.list(),
      source: entry =>
        new DatasetQueryClient({
          context: entry.contextName,
          dataset: entry.name,
          fetcher: target,
        }),
    },
    (entry, error) =>
      console.error(
        `[analytics] dataset ${entry.contextName}.${entry.name} could not be described`,
        error,
      ),
  );
}

let shared: ViewEngine | undefined;

/** The console's one engine, its views and boards in the server's view store. */
export function analyticsEngine(target: Fetcher = fetcher): ViewEngine {
  shared ??= createAnalyticsEngine(new WowViewStore({ fetcher: target }));
  return shared;
}
