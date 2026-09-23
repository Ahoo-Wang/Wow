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
  DASHBOARD_GRID_COLUMNS,
  LEGACY_GRID_COLUMNS,
  type DashboardViewConfig,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';

const SCALE = DASHBOARD_GRID_COLUMNS / LEGACY_GRID_COLUMNS;

/**
 * A stored config read into the form this engine writes (D22 E).
 *
 * A config that does not say which grid it is in (`columns`) was written for
 * the twelve-column grid: every `x` and `w` is doubled and every `y` and `h`
 * kept, which puts each panel on exactly the pixels it had — a column of the
 * new grid and the gap after it are half of one of the old, so a panel
 * twice as many columns wide spans the same width. It gains `columns` and an
 * empty `tabs`, so a save writes the new form and the next read leaves it
 * alone.
 *
 * A config that says anything about `columns` is not guessed at: one in this
 * form comes back as it is (the same object, so nothing downstream sees a
 * change), and one naming another grid is left for admission to refuse
 * (`dashboard.grid.unsupported`). Nothing here throws on a malformed config
 * either — whatever is not a panel with numbers is carried over untouched
 * and reported where admission reports it.
 */
export function migrateDashboardConfig(
  config: DashboardViewConfig,
): DashboardViewConfig {
  const stored: unknown = config;
  if (!isPlainObject(stored) || 'columns' in stored) return config;
  const panels: unknown = stored.panels;
  return {
    ...(stored as unknown as DashboardViewConfig),
    columns: DASHBOARD_GRID_COLUMNS,
    tabs: [],
    panels: (Array.isArray(panels)
      ? panels.map(widened)
      : panels) as DashboardViewConfig['panels'],
  };
}

function widened(panel: unknown): unknown {
  if (!isPlainObject(panel) || !isPlainObject(panel.layout)) return panel;
  const layout = panel.layout;
  return {
    ...panel,
    layout: { ...layout, x: scaled(layout.x), w: scaled(layout.w) },
  };
}

function scaled(value: unknown): unknown {
  return typeof value === 'number' ? value * SCALE : value;
}
