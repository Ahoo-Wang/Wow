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

import { DescriptionCapable } from '../types';

export interface ScopesCapable {
  scopes: string[];
}

export interface Aggregate extends ScopesCapable {
  /**
   * Aggregate type fully qualified name
   */
  type: string | null;
  /**
   * Static tenant ID
   */
  tenantId: string | null;
  /**
   * Custom ID generator name
   */
  id: string | null;
  /**
   * Command type fully qualified name
   */
  commands: string[];
  /**
   * Domain Event type fully qualified name
   */
  events: string[];
}

export interface BoundedContext extends ScopesCapable, DescriptionCapable {
  alias: string | null;
  aggregates: Record<string, Aggregate>;
}

export interface WowMetadata extends DescriptionCapable {
  contexts: Record<string, BoundedContext>;
}
