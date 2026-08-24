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

import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { SnapshotQueryApi, SnapshotQueryClient } from '../../../src';
import {
  AggregationMetricType,
  SnapshotQueryEndpointPaths,
  type AggregationQuery,
  type DynamicDocument,
} from '../../../src';

describe('SnapshotQueryEndpointPaths', () => {
  it('should have correct endpoint path values', () => {
    expect(SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME).toBe('snapshot');
    expect(SnapshotQueryEndpointPaths.AGGREGATION).toBe('snapshot/aggregation');
    expect(SnapshotQueryEndpointPaths.COUNT).toBe('snapshot/count');
    expect(SnapshotQueryEndpointPaths.LIST).toBe('snapshot/list');
    expect(SnapshotQueryEndpointPaths.LIST_STATE).toBe('snapshot/list/state');
    expect(SnapshotQueryEndpointPaths.PAGED).toBe('snapshot/paged');
    expect(SnapshotQueryEndpointPaths.PAGED_STATE).toBe('snapshot/paged/state');
    expect(SnapshotQueryEndpointPaths.SINGLE).toBe('snapshot/single');
    expect(SnapshotQueryEndpointPaths.SINGLE_STATE).toBe(
      'snapshot/single/state',
    );
  });

  it('exposes typed JSON and SSE aggregation results', () => {
    type RootFields = 'state.status';
    type AggregationRow = DynamicDocument & {
      product: string;
      total: number;
    };
    type SnapshotApiHasAggregationKeys =
      'aggregate' extends keyof SnapshotQueryApi<unknown, RootFields>
        ? 'aggregateStream' extends keyof SnapshotQueryApi<unknown, RootFields>
          ? true
          : false
        : false;
    type SnapshotApiRequiresAggregation =
      SnapshotQueryApi<unknown, RootFields> extends {
        aggregate: unknown;
        aggregateStream: unknown;
      }
        ? true
        : false;

    const query: AggregationQuery<RootFields> = {
      metrics: [{ type: AggregationMetricType.COUNT, alias: 'total' }],
    };
    const assertClientTypes = (
      client: SnapshotQueryClient<unknown, RootFields>,
    ) => {
      expectTypeOf(client.aggregate<AggregationRow>(query)).toEqualTypeOf<
        Promise<AggregationRow[]>
      >();
      expectTypeOf(client.aggregateStream<AggregationRow>(query)).toEqualTypeOf<
        Promise<ReadableStream<JsonServerSentEvent<AggregationRow>>>
      >();
    };

    expectTypeOf<SnapshotApiHasAggregationKeys>().toEqualTypeOf<true>();
    expectTypeOf<SnapshotApiRequiresAggregation>().toEqualTypeOf<false>();
    expectTypeOf(assertClientTypes).toBeFunction();
  });
});
