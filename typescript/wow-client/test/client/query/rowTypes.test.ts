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
 * The row and body types the query clients answer when a caller names none
 * are `unknown`-valued, and a row type the caller names may be an interface.
 * An interface has no index signature, so a `Record<string, unknown>`
 * constraint would reject it; the aggregate methods take `Row extends object`.
 */

import { describe, expectTypeOf, it } from 'vitest';
import type {
  AggregationQuery,
  CommandResult,
  DomainEventStream,
  DynamicDocument,
  EventStreamQueryClient,
  QueryApi,
  SnapshotQueryClient,
  StateEvent,
} from '../../../src';

interface TrendRow {
  bucket: number;
  streamCount: number;
}

declare const query: AggregationQuery;
declare const api: QueryApi<unknown>;
declare const snapshots: SnapshotQueryClient<unknown>;
declare const events: EventStreamQueryClient;

/** Never called: its inferred return type is what the test reads. */
function aggregateUnnamed() {
  return api.aggregate(query);
}

/** What an aggregate method resolves to. */
type Rows<M extends (...args: never[]) => Promise<unknown>> = Awaited<
  ReturnType<M>
>;

describe('row types', () => {
  it('answers unknown values when the caller names no row type', () => {
    expectTypeOf<DynamicDocument>().toEqualTypeOf<Record<string, unknown>>();
    expectTypeOf<Rows<typeof aggregateUnnamed>>().toEqualTypeOf<
      DynamicDocument[]
    >();
    expectTypeOf<CommandResult['result']>().toEqualTypeOf<
      Record<string, unknown>
    >();
  });

  it('takes an interface as the row type', () => {
    expectTypeOf<Rows<typeof api.aggregate<TrendRow>>>().toEqualTypeOf<
      TrendRow[]
    >();
    expectTypeOf<Rows<typeof snapshots.aggregate<TrendRow>>>().toEqualTypeOf<
      TrendRow[]
    >();
    expectTypeOf<Rows<typeof events.aggregate<TrendRow>>>().toEqualTypeOf<
      TrendRow[]
    >();
  });

  it('defaults event bodies and states to unknown', () => {
    expectTypeOf<
      DomainEventStream['body'][number]['body']
    >().toEqualTypeOf<unknown>();
    expectTypeOf<StateEvent['state']>().toEqualTypeOf<unknown>();
    expectTypeOf<typeof events>().toEqualTypeOf<
      EventStreamQueryClient<unknown>
    >();
  });
});
