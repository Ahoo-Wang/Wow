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

import { moveElement, verticalCompactor, type Layout } from 'react-grid-layout';
import type { DashboardPanel } from './dashboardModel.js';
import type { DeepReadonly } from '../lib/types.js';

type Panels = readonly DeepReadonly<DashboardPanel>[];
export function toGridLayout(panels: Panels): Layout {
  return panels.map(panel => ({
    i: panel.id,
    ...panel.layout,
    minW: 1,
    maxW: 12,
    minH: 1,
    maxH: 100,
  }));
}
export function applyGridLayout(panels: Panels, layout: Layout): Panels {
  const next = panels.map(panel => {
    const item = layout.find(item => item.i === panel.id);
    if (!item) throw new Error('面板位置已失效');
    const { x, y, w, h } = item;
    if (
      ![x, y, w, h].every(Number.isInteger) ||
      x < 0 ||
      y < 0 ||
      w < 1 ||
      x + w > 12 ||
      h < 1 ||
      h > 100 ||
      y + h > 10000
    )
      throw new Error('面板位置或尺寸超出范围');
    return x === panel.layout.x &&
      y === panel.layout.y &&
      w === panel.layout.w &&
      h === panel.layout.h
      ? panel
      : { ...panel, layout: { x, y, w, h } };
  });
  return next.every((panel, index) => panel === panels[index]) ? panels : next;
}
/** Keyboard/numeric edits use the same library movement and compaction as pointer edits. */
export function updatePanelLayout(
  panels: Panels,
  id: string,
  geometry: DashboardPanel['layout'],
): Panels {
  applyGridLayout(
    panels,
    toGridLayout(panels).map(item =>
      item.i === id ? { ...item, ...geometry } : item,
    ),
  );
  const layout = toGridLayout(panels);
  const item = layout.find(item => item.i === id);
  if (!item) throw new Error('面板位置已失效');
  if (
    Object.entries(geometry).every(
      ([key, value]) => item[key as keyof typeof geometry] === value,
    )
  )
    return panels;
  item.w = geometry.w;
  item.h = geometry.h;
  const moved = moveElement(
    layout,
    item,
    geometry.x,
    geometry.y,
    true,
    false,
    'vertical',
    12,
  );
  return applyGridLayout(panels, verticalCompactor.compact(moved, 12));
}
/** Restore geometry only; adding/removing/replacing references stays independent. */
export function restorePanelLayouts(panels: Panels, previous: Panels): Panels {
  return applyGridLayout(
    panels,
    toGridLayout(panels).map(item => {
      const old = previous.find(panel => panel.id === item.i);
      return old ? { ...item, ...old.layout } : item;
    }),
  );
}
