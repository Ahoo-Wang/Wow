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
import { text } from '../src/index.js';
import { admit } from '../src/testing/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
} from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

/**
 * `/testing`'s `admit` (host-integration.md 6): everything a host declares,
 * admitted as the engine admits it, over the descriptors it commits — the
 * one line a host's CI runs.
 */
describe('admit', () => {
  const descriptors = { orders: ordersDescriptor() };

  it('finds nothing in declarations that hold', () => {
    expect(
      admit([ordersDefinition(), overviewDefinition()], descriptors),
    ).toEqual([]);
  });

  it('says each finding with the definition it is about', () => {
    const board = overviewDefinition({
      views: [
        {
          id: 'board',
          title: text('board.title'),
          config: dashboardConfig({
            panels: [
              {
                id: 'gone',
                kind: 'view',
                instanceId: 'system:orders:gone',
                bindings: [],
                layout: { x: 0, y: 0, w: 6, h: 4 },
              },
            ],
          }),
        },
      ],
    });
    const found = admit(
      // A resource is taken as it is registered (host-integration.md 4).
      [{ definition: ordersDefinition() }, board],
      descriptors,
      { text: key => (key === 'board.title' ? 'Board' : undefined) },
    );
    expect(found).toEqual([
      expect.objectContaining({
        definition: 'overview',
        code: 'dashboard.panel.view-undeclared',
        severity: 'error',
      }),
    ]);
  });

  it('narrows each definition to its descriptor, and says what that took', () => {
    const lacking = ordersDescriptor({
      fields: ['id', 'warehouse', 'status'].map(path => describedField(path)),
    });
    const found = admit([ordersDefinition()], { orders: lacking });
    expect(found.map(entry => [entry.definition, entry.code])).toContainEqual([
      'orders',
      'capability.field.unknown',
    ]);
  });

  it('says a source it was given no descriptor for goes unchecked', () => {
    expect(admit([ordersDefinition()], {})).toEqual([
      expect.objectContaining({
        definition: 'orders',
        code: 'definition.descriptor.missing',
        params: { source: 'orders' },
        severity: 'warning',
      }),
    ]);
  });
});
