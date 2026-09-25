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
  AggregationDatePart,
  AggregationDateUnit,
  AggregationMetricType,
  FilterOperator,
  PagingMode,
  QueryModels,
  QueryValueKind,
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
    expect(descriptor.variants).toBeUndefined();
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
    // MongoDB searches no element's fields, and the record's search never
    // lists a field inside an element.
    expect(descriptor.elements.length).toBeGreaterThan(0);
    for (const element of descriptor.elements) {
      expect(element.search).toBeUndefined();
      for (const field of descriptor.record.search?.fields ?? [])
        expect(field.startsWith(`${element.path}.`)).toBe(false);
    }
    expect(productId?.filter.operators).toEqual(
      expect.arrayContaining([FilterOperator.EQ, FilterOperator.IN]),
    );
    const aggregateId = descriptor.fields.find(
      field => field.path === 'aggregateId',
    );
    expect(aggregateId?.role).toBe('AGGREGATE_ID');
    expect(aggregateId?.sort).toEqual({ paged: true, cursor: true });
    // Every field lists its aliases, none when it has none.
    for (const field of descriptor.fields)
      expect(Array.isArray(field.aliases)).toBe(true);

    // The server runs on MongoDB here, which estimates percentiles only.
    expect(descriptor.analysis.approximate).toEqual([
      AggregationMetricType.PERCENTILE,
    ]);
    expect([...descriptor.analysis.dateUnits].sort()).toEqual(
      Object.values(AggregationDateUnit).sort(),
    );
    // FIRST and LAST order by the snapshot's event time by default; having
    // cannot test them.
    expect(descriptor.analysis.metrics).toEqual(
      expect.arrayContaining(['FIRST', 'LAST']),
    );
    expect(descriptor.analysis.having.metrics).not.toContain('FIRST');
    expect(descriptor.analysis.having.metrics).not.toContain('LAST');
    expect(descriptor.analysis.firstLastOrderBy).toBe('eventTime');
    expect(aggregateId?.aggregate?.firstLast).toBe(true);
    expect([...descriptor.analysis.dateParts].sort()).toEqual(
      Object.values(AggregationDatePart).sort(),
    );
    // A time field groups by calendar buckets and by calendar parts alike.
    const firstEventTime = descriptor.fields.find(
      field => field.path === 'firstEventTime',
    );
    expect(firstEventTime?.aggregate?.groups).toEqual(
      expect.arrayContaining(['DATE_HISTOGRAM', 'DATE_PART']),
    );

    // One entry per pattern: the tags map's values are arrays, described once.
    const patterns = descriptor.dynamic.map(dynamic => dynamic.pattern);
    expect(new Set(patterns).size).toBe(patterns.length);
    const tags = descriptor.dynamic.filter(
      dynamic => dynamic.pattern === 'tags.{key}',
    );
    expect(tags).toHaveLength(1);
    expect(tags[0].kind).toBe(QueryValueKind.ARRAY);
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
    // Each event type is a variant of `body`, its payload fields relative
    // to that element.
    const variants = descriptor.variants;
    expect(variants).toMatchObject({
      element: 'body',
      discriminator: 'bodyType',
    });
    const values = variants?.values.map(variant => variant.value) ?? [];
    expect(values).toEqual([...values].sort());
    const added = variants?.values.find(
      variant => variant.value === 'me.ahoo.wow.example.api.cart.CartItemAdded',
    );
    expect(added?.fields.map(field => field.path)).toContain(
      'body.added.productId',
    );
    await expect(
      descriptors.describeEventStream(result.version),
    ).resolves.toEqual({ notModified: true, version: result.version });
  });
});
