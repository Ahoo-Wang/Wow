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

import type { Fetcher } from '@ahoo-wang/fetcher';
import type { SystemViewBody } from '../wire.js';
import { request } from './errors.js';

/**
 * The system views route, `GET /view-store/system-views[/{id}]`: the
 * service's own route, ordered before Wow's and outside the Manifest, so no
 * client is generated for it. It answers the configured system views of the
 * caller's tenant and application and the stored ones, in the 9.x
 * `SystemView` shape; the tenant is the principal's, the application the
 * request's `CoSec-App-Id`.
 */
export const SYSTEM_VIEWS_PATH = '/view-store/system-views';

/** The system views route, through the store's fetcher. */
export class SystemViewsClient {
  constructor(private readonly fetcher: Fetcher) {}

  /** The definition's system views: the configured ones first, in their order. */
  list(definitionId: string, signal?: AbortSignal): Promise<SystemViewBody[]> {
    return this.get(
      `${SYSTEM_VIEWS_PATH}?definitionId=${encodeURIComponent(definitionId)}`,
      signal,
    );
  }

  /** One system view; `404 view-store.not-found` for none. */
  one(id: string, signal?: AbortSignal): Promise<SystemViewBody> {
    return this.get(`${SYSTEM_VIEWS_PATH}/${encodeURIComponent(id)}`, signal);
  }

  private get<T>(url: string, signal: AbortSignal | undefined): Promise<T> {
    return request(async () => {
      const response = await this.fetcher.fetch(url, {
        method: 'GET',
        ...(signal === undefined ? {} : { signal }),
      });
      return (await response.json()) as T;
    });
  }
}
