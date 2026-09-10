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

import type { ViewPreferenceService } from '@ahoo-wang/fetcher-view-engine';
import type { HttpViewTransport } from './HttpViewTransport.js';
export class HttpViewPreferenceService implements ViewPreferenceService {
  constructor(private readonly transport: HttpViewTransport) {}
  readonly saveDefault = async (
    id: string,
    instanceId: string | null,
  ): Promise<void> => {
    this.transport.assertDefinition(id);
    await this.transport.request('/default', 'PUT', { instanceId });
  };
  readonly saveOrder = async (
    id: string,
    instanceIds: string[],
  ): Promise<void> => {
    this.transport.assertDefinition(id);
    await this.transport.request('/order', 'PUT', { instanceIds });
  };
}
