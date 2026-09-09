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

import { validateFilterJson } from '../../filter/filterConfigurationValidation.js';

export function assertObject(
  value: unknown,
  label: string,
): asserts value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error(`${label}必须是对象`);
}

export function assertText(
  value: unknown,
  label: string,
): asserts value is string {
  if (typeof value !== 'string' || !value.trim())
    throw new Error(`${label}不能为空`);
}

export function assertPath(value: unknown, label: string) {
  assertText(value, label);
  if (value.split('.').some(segment => !segment))
    throw new Error(`${label}包含空路径`);
}

export function validateReference(value: unknown) {
  if (value === undefined) return;
  assertObject(value, '扩展引用');
  assertText(value.name, '扩展名称');
  if (value.options !== undefined) {
    assertObject(value.options, '扩展选项');
    validateFilterJson(value.options);
    function rejectUndefined(item: unknown): void {
      if (item === undefined) throw new Error('扩展选项必须可序列化为 JSON');
      if (item && typeof item === 'object')
        Object.values(item).forEach(rejectUndefined);
    }
    rejectUndefined(value.options);
  }
}
