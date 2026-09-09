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

import type { ViewDefinition } from '@ahoo-wang/fetcher-view-engine';
import type { ViewDefinitionService } from '@ahoo-wang/fetcher-view-engine';
import type { HttpViewTransport } from './HttpViewTransport.js';
export class HttpViewDefinitionService implements ViewDefinitionService {
  constructor(private readonly transport: HttpViewTransport) {}
  readonly load = async (
    id: string,
    signal?: AbortSignal,
  ): Promise<ViewDefinition> => {
    this.transport.assertDefinition(id);
    return this.transport.request('', 'GET', undefined, signal);
  };
}
