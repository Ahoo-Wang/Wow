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
 * The query clients' endpoint paths are the server's: each one, under the
 * example domain's `cart` aggregate, is the path of the route whose id the
 * server gives that query (Kotlin `RouteSuffixes` and the route contributors,
 * pinned by the committed contract snapshot).
 */

import { describe, expect, it } from 'vitest';
import { EventStreamQueryEndpointPaths } from '../../../src/client/query/event/endpointPaths';
import { SnapshotQueryEndpointPaths } from '../../../src/client/query/snapshot/endpointPaths';
import { contractRoutes } from '../../fixtures/serverContract.js';

const AGGREGATE = 'example.cart';

/** The route id suffix of each endpoint: what the server calls it. */
const SNAPSHOT_ROUTE_IDS: Record<
  keyof typeof SnapshotQueryEndpointPaths,
  string
> = {
  AGGREGATION: 'snapshot.aggregation',
  COUNT: 'snapshot.count',
  LIST: 'snapshot.list_query',
  LIST_STATE: 'snapshot_state.list_query',
  PAGED: 'snapshot.paged_query',
  PAGED_STATE: 'snapshot_state.paged_query',
  CURSOR: 'snapshot.cursor_query',
  CURSOR_STATE: 'snapshot_state.cursor_query',
  SINGLE: 'snapshot.single',
  SINGLE_STATE: 'snapshot_state.single',
};

const EVENT_ROUTE_IDS: Record<
  keyof typeof EventStreamQueryEndpointPaths,
  string
> = {
  AGGREGATION: 'event.aggregation',
  COUNT: 'event.count',
  LIST: 'event.list_query',
  PAGED: 'event.paged_query',
  CURSOR: 'event.cursor_query',
  LOAD: 'event_stream.load',
};

function routePath(routeIdSuffix: string): string | undefined {
  return contractRoutes.find(
    route => route.id === `${AGGREGATE}.${routeIdSuffix}`,
  )?.path;
}

describe('endpoint paths match the server contract', () => {
  it.each(Object.entries(SnapshotQueryEndpointPaths))(
    'SnapshotQueryEndpointPaths.%s',
    (key, path) => {
      const routeId =
        SNAPSHOT_ROUTE_IDS[key as keyof typeof SNAPSHOT_ROUTE_IDS];
      expect(routePath(routeId)).toBe(`/cart/${path}`);
    },
  );

  it.each(Object.entries(EventStreamQueryEndpointPaths))(
    'EventStreamQueryEndpointPaths.%s',
    (key, path) => {
      const routeId = EVENT_ROUTE_IDS[key as keyof typeof EVENT_ROUTE_IDS];
      expect(routePath(routeId)).toBe(`/cart/${path}`);
    },
  );
});
