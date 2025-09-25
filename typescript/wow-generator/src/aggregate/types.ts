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

import { Reference, Schema } from '@ahoo-wang/fetcher-openapi';

export interface EventStreamSchema extends Schema {
  properties: {
    body: {
      items: {
        anyOf: Array<DomainEventSchema>;
      }
    };
  };
}

interface DomainEventSchema extends Schema {
  type: 'object';
  title: string;
  properties: {
    name: {
      type: 'string';
      const: string;
    };
    bodyType: {
      type: 'string';
      const: string;
    },
    body: Reference
  };
}