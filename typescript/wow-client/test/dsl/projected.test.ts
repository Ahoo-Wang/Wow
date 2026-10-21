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
import {
  listQuery,
  type PickPaths,
  type Projected,
  projection,
} from '../../src/dsl.js';
import type { SnapshotQueryClient, SnapshotRecord } from '../../src/index.js';

interface Line {
  sku: string;
  quantity: number;
}

interface Cart {
  name: string;
  note?: string;
  address: { city: string; street: string } | null;
  lines: Line[];
}

type CartField =
  | 'state.name'
  | 'state.note'
  | 'state.address'
  | 'state.address.city'
  | 'state.lines'
  | 'state.lines.sku';

// Read by `typeof` alone: what the client's methods return.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
declare const client: SnapshotQueryClient<Cart, CartField>;

describe('projected records (review R4-Q, Q-14)', () => {
  it('narrows a record to the included paths, keeping descendants, arrays and nullability', () => {
    expectTypeOf<
      PickPaths<Cart, 'name' | 'address.city' | 'lines.sku'>
    >().toEqualTypeOf<{
      name: string;
      address: { city: string } | null;
      lines: { sku: string }[];
    }>();
    expectTypeOf<PickPaths<Cart, 'note' | 'address'>>().toEqualTypeOf<{
      note?: string;
      address: { city: string; street: string } | null;
    }>();
  });

  it('keeps the whole record without literal includes', () => {
    expectTypeOf<Projected<Cart, string>>().toEqualTypeOf<Cart>();
    expectTypeOf<Projected<Cart, CartField, CartField>>().toEqualTypeOf<Cart>();
  });

  it('types what a snapshot query answers by its projection', () => {
    type Narrowed = Awaited<
      ReturnType<
        typeof client.list<'aggregateId' | 'state.name' | 'state.lines.sku'>
      >
    >[number];
    expectTypeOf<Narrowed>().toEqualTypeOf<{
      readonly aggregateId: string;
      readonly state: { name: string; lines: { sku: string }[] };
    }>();
    const query = listQuery({
      projection: projection({ include: ['state.name'] }),
    });
    expectTypeOf(query.projection?.include).toEqualTypeOf<
      'state.name'[] | undefined
    >();
    type Whole = Awaited<ReturnType<typeof client.single>>;
    expectTypeOf<Whole>().toEqualTypeOf<SnapshotRecord<Cart>>();
  });
});
