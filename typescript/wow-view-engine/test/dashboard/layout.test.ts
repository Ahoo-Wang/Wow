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

import { expect, it } from 'vitest';
import {
  applyGridLayout,
  toGridLayout,
  updatePanelLayout,
  restorePanelLayouts,
} from '../../src/dashboard/dashboardLayout.js';
const panels = ['a', 'b'].map((id, index) => ({
  kind: 'view' as const,
  id,
  instanceId: id,
  layout: { x: index * 6, y: 0, w: 6, h: 5 },
}));
it('preserves no-op identity and uses library collision/compaction without reordering panel identities', () => {
  expect(applyGridLayout(panels, toGridLayout(panels))).toBe(panels);
  expect(updatePanelLayout(panels, 'a', panels[0].layout)).toBe(panels);
  const moved = updatePanelLayout(panels, 'a', { x: 6, y: 0, w: 6, h: 5 });
  expect(moved.map(panel => panel.id)).toEqual(['a', 'b']);
  expect(moved[0].layout.x).toBe(6);
  expect(moved[1].layout.y).toBeGreaterThanOrEqual(5);
  const resized = updatePanelLayout(panels, 'a', { x: 0, y: 0, w: 12, h: 7 });
  expect(resized[0].layout.h).toBe(7);
  expect(resized[1].layout.y).toBeGreaterThanOrEqual(7);
  expect(panels[0].layout).toEqual({ x: 0, y: 0, w: 6, h: 5 });
});
it('rejects invalid geometry and restores geometry without reverting reference changes', () => {
  for (const layout of [
    { x: 7, y: 0, w: 6, h: 5 },
    { x: 0, y: 0, w: 6, h: 101 },
    { x: 0, y: 9999, w: 6, h: 5 },
    { x: 0.5, y: 0, w: 6, h: 5 },
  ])
    expect(() => updatePanelLayout(panels, 'a', layout)).toThrow();
  expect(() =>
    updatePanelLayout(panels, 'unknown', panels[0].layout),
  ).toThrow();
  const updated = panels.map(panel => ({
    kind: 'view' as const,
    ...panel,
    instanceId: 'replacement',
    layout: { ...panel.layout, h: 9 },
  }));
  const restored = restorePanelLayouts(updated, panels);
  expect(restored[0].instanceId).toBe('replacement');
  expect(restored[0].layout).toEqual(panels[0].layout);
});
