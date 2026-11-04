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

import { describe, expectTypeOf, it } from 'vitest';
import { filter } from '../src/dsl.js';
import type {
  AccessField,
  AccessValues,
  CommittedEvent,
  EventQueryClient,
  EventStream,
  NoAccess,
  Snapshot,
  SnapshotQueryClient,
  StateClient,
  StreamAccess,
} from '../src/index.js';

// Read by `typeof` alone: what the clients take and answer.

interface Document {
  title: string;
}

/** A document's access attributes, as wow-generator writes an `<Aggregate>Access`: a type alias. */
type DocumentAccess = {
  readonly region?: string | null;
  readonly teams?: readonly string[];
};

declare const documents: SnapshotQueryClient<
  Document,
  'state.title',
  DocumentAccess
>;
declare const carts: SnapshotQueryClient<Document, 'state.title', NoAccess>;
declare const streams: EventQueryClient<CommittedEvent, DocumentAccess>;
declare const cartStreams: EventQueryClient<CommittedEvent, NoAccess>;
declare const untyped: SnapshotQueryClient;
declare const states: StateClient<Document, CommittedEvent, DocumentAccess>;

describe('access attributes in the clients (D390)', () => {
  it('types a record access by the aggregate, and an event stream with its owner and space', () => {
    expectTypeOf<
      Snapshot<Document, DocumentAccess>['access']
    >().toEqualTypeOf<DocumentAccess>();
    expectTypeOf<Snapshot<Document>['access']>().toEqualTypeOf<AccessValues>();
    expectTypeOf<
      EventStream<CommittedEvent, DocumentAccess>['access']
    >().toEqualTypeOf<StreamAccess<DocumentAccess> | null | undefined>();
    expectTypeOf<StreamAccess<NoAccess>['owner']>().toEqualTypeOf<
      string | null | undefined
    >();
    expectTypeOf<AccessField<DocumentAccess>>().toEqualTypeOf<
      'access.region' | 'access.teams'
    >();
    expectTypeOf<AccessField<NoAccess>>().toBeNever();
    // A typed record is also an untyped one.
    expectTypeOf<Snapshot<Document, DocumentAccess>>().toExtend<
      Snapshot<Document>
    >();
    expectTypeOf<EventStream<CommittedEvent, DocumentAccess>>().toExtend<
      EventStream<CommittedEvent>
    >();
    expectTypeOf(() => states.get('d1'))
      .returns.resolves.toHaveProperty('access')
      .toEqualTypeOf<DocumentAccess>();
  });

  it("names the access attributes a query may filter on, where the aggregate's contract allows", () => {
    const declared = () =>
      documents.list({
        filter: filter.and([
          filter.eq('access.region', 'east'),
          filter.containsAll('access.teams', ['sales']),
        ]),
        projection: { include: ['aggregateId', 'access.region'] },
      });
    expectTypeOf(declared).returns.resolves.items.toEqualTypeOf<{
      readonly aggregateId: string;
      readonly access: { readonly region?: string | null };
    }>();
    const queries = () => [
      // @ts-expect-error the document declares no such attribute
      documents.list({ filter: filter.eq('access.owner', 'o') }),
      // @ts-expect-error a cart declares none
      carts.list({ filter: filter.eq('access.region', 'east') }),
      carts.list({ filter: filter.isEmpty('access') }),
      // An event stream's access holds the owner and space whatever the aggregate declares.
      cartStreams.list({ filter: filter.eq('access.owner', 'o') }),
      streams.list({ filter: filter.eq('access.region', 'east') }),
      // @ts-expect-error a cart declares none
      cartStreams.list({ filter: filter.eq('access.region', 'east') }),
      // A client without a contract takes any access attribute.
      untyped.list({ filter: filter.eq('access.anything', 1) }),
    ];
    expectTypeOf(queries).toBeFunction();
  });
});
