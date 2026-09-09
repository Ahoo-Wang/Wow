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

import type { ViewHost } from '@ahoo-wang/fetcher-view-engine';
import {
  HttpViewTransport,
  type HttpViewTransportOptions,
} from './HttpViewTransport.js';
import { HttpViewDefinitionService } from './HttpViewDefinitionService.js';
import { HttpViewInstanceService } from './HttpViewInstanceService.js';
import { HttpViewPreferenceService } from './HttpViewPreferenceService.js';
export interface HttpViewHostOptions extends HttpViewTransportOptions {
  /** Local source lookup; never sent to the view service. */
  resolveSource: ViewHost['resolveSource'];
}
/** Composes resource clients and the application's local source resolver. */
export class HttpViewHost implements ViewHost {
  readonly definition: HttpViewDefinitionService;
  readonly instance: HttpViewInstanceService;
  readonly preference: HttpViewPreferenceService;
  readonly permission: HttpViewTransport['permission'];
  readonly resolveSource: ViewHost['resolveSource'];
  constructor(options: HttpViewHostOptions) {
    const transport = new HttpViewTransport(options);
    this.definition = new HttpViewDefinitionService(transport);
    this.instance = new HttpViewInstanceService(transport);
    this.preference = new HttpViewPreferenceService(transport);
    this.permission = transport.permission;
    this.resolveSource = options.resolveSource.bind({ ...options });
  }
}
