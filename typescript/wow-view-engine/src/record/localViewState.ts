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

import { sameJsonState } from '../lib/snapshot.js';
import type {
  ViewDefinition,
  ViewInstance,
  ViewCreateInput,
} from '../contracts/viewModel.js';
import { validateViewInstance } from './recordValidation.js';

export type StoredInstance = ViewInstance & { ownerKey: string | null };
export interface ServiceState {
  instances: StoredInstance[];
  users: Record<string, { order: string[]; defaultInstanceId: string | null }>;
  creates: Record<string, { input: ViewCreateInput; result: ViewInstance }>;
}

/** Validate persisted service state independently of storage and locking. */
export function validateLocalViewState(
  state: ServiceState,
  definition: ViewDefinition,
): void {
  if (
    !Array.isArray(state.instances) ||
    !state.users ||
    typeof state.users !== 'object' ||
    Array.isArray(state.users) ||
    !state.creates ||
    typeof state.creates !== 'object' ||
    Array.isArray(state.creates)
  )
    throw new Error('服务存储格式无效');
  const known = new Set<string>();
  for (const item of state.instances) {
    validateViewInstance(item, definition);
    if (
      !item.revision ||
      (item.scope.type === 'personal'
        ? typeof item.ownerKey !== 'string'
        : item.ownerKey !== null)
    )
      throw new Error('实例归属或版本无效');
    const key = JSON.stringify([item.ownerKey, item.id]);
    if (known.has(key)) throw new Error('实例重复');
    known.add(key);
  }
  for (const user of Object.values(state.users))
    if (
      !user ||
      !Array.isArray(user.order) ||
      user.order.some(id => typeof id !== 'string') ||
      new Set(user.order).size !== user.order.length ||
      !(
        user.defaultInstanceId === null ||
        typeof user.defaultInstanceId === 'string'
      )
    )
      throw new Error('用户顺序无效');
  for (const receipt of Object.values(state.creates)) {
    validateViewInstance(receipt.result, definition);
    if (!sameJsonState(receipt.input, createViewInput(receipt.result)))
      throw new Error('创建回执无效');
  }
}

export function createViewInput({
  definitionId,
  kind,
  title,
  scope,
  config,
}: ViewCreateInput): ViewCreateInput {
  return { definitionId, kind, title, scope, config } as ViewCreateInput;
}
