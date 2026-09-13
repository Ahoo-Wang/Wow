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
import { validateDashboardConfig } from '../../src/dashboard/dashboardValidation.js';
import { instance } from '../engine/fixtures.js';

const panel = {
  kind: 'view' as const,
  id: 'a',
  instanceId: 'orders',
  layout: { x: 0, y: 0, w: 6, h: 18 },
};
const config = { schemaVersion: 1, panels: [panel], filters: [] };
describe('dashboard configuration admission', () => {
  it('round trips v1 and rejects unsupported versions, duplicate identities and spans', () => {
    expect(() =>
      validateDashboardConfig(JSON.parse(JSON.stringify(config))),
    ).not.toThrow();
    for (const invalid of [
      { ...config, schemaVersion: 2 },
      { ...config, panels: [panel, panel] },
      ...[0, 13, 1.5].map(w => ({
        ...config,
        panels: [{ ...panel, layout: { x: 0, y: 0, w, h: 18 } }],
      })),
      ...[
        { x: -1 },
        { y: -1 },
        { x: 7 },
        { h: 0 },
        { h: 101 },
        { y: 9990 },
        { y: Infinity },
        { x: NaN },
        { h: 1.2 },
        { h: '18' },
        { y: Number.MAX_SAFE_INTEGER },
        { columnSpan: 6 },
      ].map(change => ({
        ...config,
        panels: [{ ...panel, layout: { ...panel.layout, ...change } }],
      })),
      { ...config, nested: {} },
    ])
      expect(() => validateDashboardConfig(invalid)).toThrow();
  });
  it('requires exactly one binding or exclusion and rejects unsafe field paths', () => {
    const filter = {
      id: 'global',
      filters: instance().config.filters,
      bindings: [],
      excludedPanelIds: [],
    };
    expect(() =>
      validateDashboardConfig({ ...config, filters: [filter] }),
    ).toThrow();
    expect(() =>
      validateDashboardConfig({
        ...config,
        filters: [{ ...filter, excludedPanelIds: ['a'] }],
      }),
    ).not.toThrow();
    for (const fields of [
      { '__proto__.secret': 'state.amount' },
      { 'state.amount': 'constructor.prototype' },
    ])
      expect(() =>
        validateDashboardConfig({
          ...config,
          filters: [
            {
              ...filter,
              bindings: [
                {
                  panelId: 'a',
                  kind: 'fields',
                  fields,
                  semanticCompatibility: true,
                },
              ],
            },
          ],
        }),
      ).toThrow();
  });
  it('rejects oversized trees before recursive compilation', () => {
    const filters = instance().config.filters;
    for (let i = 0; i < 33; i++)
      filters.root = {
        id: `g${i}`,
        component: { name: 'builtin' },
        operator: 'AND' as typeof filters.root.operator,
        props: {},
        operands: [filters.root],
      };
    expect(() =>
      validateDashboardConfig({
        ...config,
        filters: [
          { id: 'deep', filters, bindings: [], excludedPanelIds: ['a'] },
        ],
      }),
    ).toThrow();
  });
});
