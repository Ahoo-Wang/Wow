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

import { NamedFetcher } from '@ahoo-wang/fetcher';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { describe, expect, expectTypeOf, it, vi } from 'vitest';
import type { EventStreamQueryApi, QueryApi } from '../../../src';
import {
  EventStreamQueryClient,
  EventStreamQueryEndpointPaths,
  aggregation,
  type AggregationQuery,
  type DynamicDocument,
} from '../../../src';

describe('EventStreamQueryEndpointPaths', () => {
  type RootFields = 'body';
  type EventFields = 'name';
  type AggregationRow = DynamicDocument & {
    eventType: string;
    count: number;
  };
  const query: AggregationQuery<RootFields, EventFields> = {
    elements: [aggregation.element('body')],
    groupBy: [aggregation.terms('name', 'eventType')],
    metrics: [aggregation.count('count')],
  };

  it('should have correct endpoint path values', () => {
    expect(EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME).toBe(
      'event',
    );
    expect(EventStreamQueryEndpointPaths.AGGREGATION).toBe('event/aggregation');
    expect(EventStreamQueryEndpointPaths.COUNT).toBe('event/count');
    expect(EventStreamQueryEndpointPaths.LIST).toBe('event/list');
    expect(EventStreamQueryEndpointPaths.PAGED).toBe('event/paged');
  });

  it('exposes aggregation through the common and event query APIs', () => {
    type QueryApiHasAggregationKeys = 'aggregate' extends keyof QueryApi<
      unknown,
      RootFields
    >
      ? 'aggregateStream' extends keyof QueryApi<unknown, RootFields>
        ? true
        : false
      : false;
    type EventApiHasAggregationKeys =
      'aggregate' extends keyof EventStreamQueryApi<unknown, RootFields>
        ? 'aggregateStream' extends keyof EventStreamQueryApi<
            unknown,
            RootFields
          >
          ? true
          : false
        : false;
    type QueryApiRequiresAggregation =
      QueryApi<unknown, RootFields> extends {
        aggregate: unknown;
        aggregateStream: unknown;
      }
        ? true
        : false;

    const assertClientTypes = (
      client: EventStreamQueryClient<unknown, RootFields>,
    ) => {
      expectTypeOf(client.aggregate<AggregationRow>(query)).toEqualTypeOf<
        Promise<AggregationRow[]>
      >();
      expectTypeOf(client.aggregateStream<AggregationRow>(query)).toEqualTypeOf<
        Promise<ReadableStream<JsonServerSentEvent<AggregationRow>>>
      >();
    };

    expectTypeOf<QueryApiHasAggregationKeys>().toEqualTypeOf<true>();
    expectTypeOf<EventApiHasAggregationKeys>().toEqualTypeOf<true>();
    expectTypeOf<QueryApiRequiresAggregation>().toEqualTypeOf<true>();
    expectTypeOf(assertClientTypes).toBeFunction();
  });

  it('forwards aggregation DSL output as the request body', async () => {
    const fetcher = new NamedFetcher('event-aggregation-body-test');
    const exchange = vi
      .spyOn(fetcher.interceptors, 'exchange')
      .mockImplementation(async current => {
        const body =
          typeof current.request.body === 'string'
            ? JSON.parse(current.request.body)
            : current.request.body;
        expect(body).toEqual(query);
        current.extractResult = vi.fn().mockResolvedValue([]);
        return current;
      });
    const client = new EventStreamQueryClient<unknown, RootFields>({
      basePath: '/order',
      fetcher,
    });

    await client.aggregate(query);

    expect(exchange).toHaveBeenCalledOnce();
  });
});
