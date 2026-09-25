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

import { describe, expect, it } from 'vitest';
import {
  FilterOperator,
  PagingMode,
  QueryModels,
  type QueryDescriptorResult,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { exampleFetcher } from '../../../src/wow';
import { cartQueryClientFactory } from '../../../src/generated';

// The schema routes have no owner segment, unlike the cart's queries; the
// factory leaves the resource attribution out. The server routes /cart, not
// /example/cart, so the context alias is cleared as in the other cases.
const descriptors = cartQueryClientFactory.createQueryDescriptorClient({
  contextAlias: '',
  fetcher: exampleFetcher,
});

function read(result: QueryDescriptorResult): QueryModelDescriptor {
  if (result.notModified) throw new Error('expected the descriptor in full');
  return result.descriptor;
}

describe('QueryDescriptorClient against the example server', () => {
  it('reads the cart snapshot descriptor', async () => {
    const result = await descriptors.describeSnapshot();
    const descriptor = read(result);

    expect(descriptor.model).toBe(QueryModels.SNAPSHOT);
    expect(descriptor.version).toMatch(/^sha256:[0-9a-f]{64}$/);
    expect(result.version).toBe(descriptor.version);
    expect(descriptor.record.identity).toBe('aggregateId');
    expect(descriptor.record.paging).toEqual([
      PagingMode.LIST,
      PagingMode.PAGED,
      PagingMode.CURSOR,
    ]);
    // The default HTTP budget (wow.query.http.*).
    expect(descriptor.limits.defaultListSize).toBe(100);
    expect(descriptor.limits.maxPageSize).toBe(100);

    const productId = descriptor.fields.find(
      field => field.path === 'state.items.productId',
    );
    expect(productId).toMatchObject({ scope: 'state.items' });
    expect(productId?.filter.operators).toEqual(
      expect.arrayContaining([FilterOperator.EQ, FilterOperator.IN]),
    );
    const aggregateId = descriptor.fields.find(
      field => field.path === 'aggregateId',
    );
    expect(aggregateId?.role).toBe('AGGREGATE_ID');
    expect(aggregateId?.sort).toEqual({ paged: true, cursor: true });
  });

  it('answers 304 to the version it holds, as a version or as the ETag', async () => {
    const { version } = await descriptors.describeSnapshot();

    await expect(descriptors.describeSnapshot(version)).resolves.toEqual({
      notModified: true,
      version,
    });
    await expect(descriptors.describeSnapshot(`"${version}"`)).resolves.toEqual(
      { notModified: true, version },
    );
  });

  it('reads in full again when the version held is stale', async () => {
    const result = await descriptors.describeSnapshot(
      'sha256:0000000000000000000000000000000000000000000000000000000000000000',
    );
    expect(result.notModified).toBe(false);
  });

  it('reads the cart event stream descriptor, and revalidates it', async () => {
    const result = await descriptors.describeEventStream();
    const descriptor = read(result);

    expect(descriptor.model).toBe(QueryModels.EVENT_STREAM);
    expect(descriptor.fields.map(field => field.path)).toContain('aggregateId');
    await expect(
      descriptors.describeEventStream(result.version),
    ).resolves.toEqual({ notModified: true, version: result.version });
  });
});
