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

import {
  OrderWorkbench,
  type OrderWorkbenchProps,
} from '../examples/react/sales-order/OrderWorkbench.js';
import { orderDefinition } from '../examples/react/sales-order/views.js';
import { HttpViewHost } from './http/HttpViewHost.js';
export interface HttpOrderExampleProps extends Omit<
  OrderWorkbenchProps,
  'createViewHost'
> {
  viewServiceUrl?: string;
  viewServiceTimeoutMs?: number;
  accessToken?: string;
}
/** Internal protocol experiment, excluded from the published package and copyable example. */
export function HttpOrderExample({
  viewServiceUrl = 'http://127.0.0.1:6010/view-service/',
  viewServiceTimeoutMs,
  accessToken,
  ...props
}: HttpOrderExampleProps) {
  return (
    <OrderWorkbench
      {...props}
      key={JSON.stringify([viewServiceUrl, viewServiceTimeoutMs])}
      createViewHost={resolveSource =>
        new HttpViewHost({
          baseUrl: viewServiceUrl,
          definitionId: orderDefinition.id,
          timeoutMs: viewServiceTimeoutMs,
          headers: (): HeadersInit =>
            accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
          resolveSource,
        })
      }
    />
  );
}
