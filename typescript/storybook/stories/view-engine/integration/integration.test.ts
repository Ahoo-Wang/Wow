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

import { describe, expect, it } from 'vitest';
import {
  BUILTIN_FIELD_KINDS,
  validateDefinition,
  validateRecord,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';
import { ordersDefinition } from './ordersDefinition.js';

/*
 * The walkthrough's definition is quoted as the code a host copies, so it
 * must be one the engine admits: a definition that reads plausibly can still
 * be refused, and nothing but running it says which.
 */
describe('the integration walkthrough’s definition', () => {
  const kinds = new Map(BUILTIN_FIELD_KINDS.map(kind => [kind.id, kind]));

  it('is admitted, and so is its system view', () => {
    expect(validateDefinition(ordersDefinition, kinds)).toEqual([]);
    for (const view of ordersDefinition.views ?? [])
      expect([
        view.id,
        validateRecord(
          ordersDefinition,
          view.config as RecordViewConfig,
          kinds,
        ),
      ]).toEqual([view.id, []]);
  });
});
