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

import type { QueryModelDescriptor } from '../../../dsl/descriptor.js';

/** The descriptor was read in full: the server had none matching the version sent, or none was sent. */
export interface QueryDescriptorRead {
  /** `false`: `descriptor` holds the descriptor. */
  notModified: false;
  /** The descriptor the server answered with. */
  descriptor: QueryModelDescriptor;
  /** Its version, the same as `descriptor.version`; send it next time. */
  version: string;
}

/**
 * The server answered 304 Not Modified: the descriptor still has the version
 * sent, so the copy the caller holds is current.
 */
export interface QueryDescriptorNotModified {
  /** `true`: keep the descriptor already held. */
  notModified: true;
  /** The version sent, without ETag quotes. */
  version: string;
}

/**
 * What {@link QueryDescriptorApi} answers: the descriptor, or word that the
 * one the caller holds is current. Branch on `notModified`.
 */
export type QueryDescriptorResult =
  QueryDescriptorRead | QueryDescriptorNotModified;

/**
 * Reads an aggregate's query capability descriptors: what its snapshot and
 * event stream query models admit (fields, operators, sort, paging,
 * aggregation, limits). {@link QueryDescriptorClient} implements it; code
 * written against the interface can take a test double.
 *
 * Each method takes the version of a descriptor already held, as
 * `descriptor.version` or as the ETag the server sent (quoted, or weak), and
 * sends it as `If-None-Match`: an unchanged descriptor then answers 304
 * without a body, and the method resolves to
 * `{ notModified: true, version }`. Any other failure rejects as the query
 * methods do; `toWowError` reads it.
 */
export interface QueryDescriptorApi {
  /**
   * Reads the descriptor of the snapshot query model,
   * `GET {aggregate}/snapshot/schema`.
   *
   * @param previous - The version or ETag of the descriptor already held; omit it to read in full.
   * @param attributes - Optional shared attributes for the interceptors
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   */
  describeSnapshot(
    previous?: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<QueryDescriptorResult>;

  /**
   * Reads the descriptor of the event stream query model,
   * `GET {aggregate}/event/schema`.
   *
   * @param previous - The version or ETag of the descriptor already held; omit it to read in full.
   * @param attributes - Optional shared attributes for the interceptors
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   */
  describeEventStream(
    previous?: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<QueryDescriptorResult>;
}
