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

import type {
  ApiMetadata,
  ApiMetadataCapable,
} from '@ahoo-wang/fetcher-decorator';
import type {
  QueryDescriptorApi,
  QueryDescriptorResult,
} from './queryDescriptorApi.js';
import {
  descriptorVersion,
  ifNoneMatch,
  QueryDescriptorEndpoints,
  readDescriptor,
} from './descriptorEndpoints.js';
import { bindMethods } from '../../bindMethods.js';

/**
 * Reads an aggregate's query capability descriptors,
 * `GET {aggregate}/snapshot/schema` and `GET {aggregate}/event/schema`: what
 * each query model admits, as a `QueryModelDescriptor`. A view or form reads
 * it to offer only the fields, operators, sorts and paging the server
 * accepts, and to know the limits.
 *
 * The base path names the aggregate (`{contextAlias}/{aggregateName}`) with
 * no tenant or owner segment: the schema routes have neither, whatever
 * routes the aggregate's queries take. `QueryClientFactory.createQueryDescriptorClient`
 * builds it that way. Headers such as `Wow-Space-Id` come from the
 * `ApiMetadata` as for the other clients.
 *
 * A descriptor does not depend on the caller and changes only when the
 * server's model or configuration does, so hold it and revalidate: pass the
 * version held, and a 304 answers `{ notModified: true }` without a body.
 *
 * @example
 * ```typescript
 * const descriptors = new QueryDescriptorClient({ basePath: 'example/cart' });
 *
 * const first = await descriptors.describeSnapshot();
 * if (!first.notModified) {
 *   const sku = first.descriptor.fields.find(f => f.path === 'state.items.productId');
 *   console.log(sku?.filter.operators);
 * }
 *
 * // Later: revalidate the copy held.
 * const again = await descriptors.describeSnapshot(first.version);
 * console.log(again.notModified); // true while the model is unchanged
 * ```
 */
export class QueryDescriptorClient
  implements QueryDescriptorApi, ApiMetadataCapable
{
  /**
   * @param apiMetadata - The fetcher, the base path naming the aggregate, and any shared headers.
   */
  constructor(public readonly apiMetadata?: ApiMetadata) {
    bindMethods(this);
  }

  /**
   * Reads the descriptor of the snapshot query model,
   * `GET {aggregate}/snapshot/schema`.
   *
   * @param previous - The version or ETag of the descriptor already held; omit it to read in full.
   * @param attributes - Optional shared attributes for the interceptors
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns The descriptor, or `{ notModified: true, version }` when `previous` is current.
   */
  async describeSnapshot(
    previous?: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<QueryDescriptorResult> {
    const version = descriptorVersion(previous);
    const exchange = await new QueryDescriptorEndpoints(
      this.apiMetadata,
    ).snapshot(ifNoneMatch(version), attributes, abort);
    return readDescriptor(exchange, version);
  }

  /**
   * Reads the descriptor of the event stream query model,
   * `GET {aggregate}/event/schema`.
   *
   * @param previous - The version or ETag of the descriptor already held; omit it to read in full.
   * @param attributes - Optional shared attributes for the interceptors
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns The descriptor, or `{ notModified: true, version }` when `previous` is current.
   */
  async describeEventStream(
    previous?: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<QueryDescriptorResult> {
    const version = descriptorVersion(previous);
    const exchange = await new QueryDescriptorEndpoints(
      this.apiMetadata,
    ).eventStream(ifNoneMatch(version), attributes, abort);
    return readDescriptor(exchange, version);
  }
}
