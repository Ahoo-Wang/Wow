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

/**
 * A board's tabs (D22 E): what admission asks of them, and the four edits
 * the tab bar makes — add, rename, reorder, remove. Moving a panel to a tab
 * is a panel edit, in `edit.ts`, since it places the panel.
 *
 * Zero or one tab draws no tab bar. A board gets its first tab when a second
 * is added: the panels it already has go onto the first, which is named by
 * whoever adds (the kernel has no words of its own). Removing a tab removes
 * its panels with it; the last tab is not removed — a board with one tab
 * reads as a board with none, and keeping its title keeps it for the day a
 * second one is added again.
 */

import {
  MAX_DASHBOARD_TABS,
  type DashboardTab,
  type DashboardViewConfig,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue, isPlainObject } from '../filter/index.js';
import { freshId, panelTab } from './panels.js';

/**
 * The tabs as admission reads them. An id is how a panel names its tab, so
 * one that is missing or said twice is an error of the board; a blank title
 * only leaves the tab to be called by its place, so it is a warning.
 */
export function validateTabs(tabs: readonly DashboardTab[]): Issue[] {
  if (tabs.length > MAX_DASHBOARD_TABS)
    return [
      issue('dashboard.tabs.too-many', ['tabs'], { max: MAX_DASHBOARD_TABS }),
    ];
  const issues: Issue[] = [];
  const seen = new Set<string>();
  tabs.forEach((tab, index) => {
    const path: IssuePath = ['tabs', index];
    if (!isPlainObject(tab)) {
      issues.push(
        issue('dashboard.shape.invalid', path, { expected: 'object' }),
      );
      return;
    }
    if (typeof tab.id !== 'string' || tab.id.trim().length === 0)
      issues.push(issue('dashboard.tab.id-empty', [...path, 'id']));
    else if (seen.has(tab.id))
      issues.push(
        issue('dashboard.tab.id-duplicate', [...path, 'id'], { id: tab.id }),
      );
    else seen.add(tab.id);
    if (typeof tab.title !== 'string' || tab.title.trim().length === 0)
      issues.push(
        issue('dashboard.tab.title-empty', [...path, 'title'], {}, 'warning'),
      );
  });
  return issues;
}

/**
 * A board with one more tab, last, and that tab's id — or `null` when the
 * title is blank or the board holds as many tabs as it may. On a board
 * without tabs the panels it has go onto a first tab called `firstTitle`,
 * and the new one is the second.
 */
export function addTab(
  config: DashboardViewConfig,
  title: string,
  firstTitle: string,
): { config: DashboardViewConfig; id: string } | null {
  const name = title.trim();
  const tabs = config.tabs;
  const adding = tabs.length === 0 ? 2 : 1;
  if (name.length === 0 || tabs.length + adding > MAX_DASHBOARD_TABS)
    return null;
  if (tabs.length > 0) {
    const id = freshId(
      tabs.map(tab => tab.id),
      'tab',
    );
    return { config: { ...config, tabs: [...tabs, { id, title: name }] }, id };
  }
  const firstName = firstTitle.trim();
  if (firstName.length === 0) return null;
  const first = freshId([], 'tab');
  const id = freshId([first], 'tab');
  return {
    config: {
      ...config,
      tabs: [
        { id: first, title: firstName },
        { id, title: name },
      ],
      panels: config.panels.map(panel => ({ ...panel, tab: first })),
    },
    id,
  };
}

/** A tab renamed; the same board for a blank title or a tab it lacks. */
export function renameTab(
  config: DashboardViewConfig,
  id: string,
  title: string,
): DashboardViewConfig {
  const name = title.trim();
  const at = config.tabs.findIndex(tab => tab.id === id);
  if (name.length === 0 || at < 0 || config.tabs[at].title === name)
    return config;
  const tabs = [...config.tabs];
  tabs[at] = { ...tabs[at], title: name };
  return { ...config, tabs };
}

/** A tab moved to `index` in the bar (clamped to it); the same board when it stays. */
export function moveTab(
  config: DashboardViewConfig,
  id: string,
  index: number,
): DashboardViewConfig {
  const from = config.tabs.findIndex(tab => tab.id === id);
  if (from < 0) return config;
  const to = Math.min(Math.max(0, Math.trunc(index)), config.tabs.length - 1);
  if (to === from) return config;
  const tabs = [...config.tabs];
  const [tab] = tabs.splice(from, 1);
  tabs.splice(to, 0, tab);
  return { ...config, tabs };
}

/**
 * A board without one of its tabs and the panels on it; the same board for
 * a tab it lacks or its last tab. A UI asks first when the tab holds panels
 * (D22 E); this is what happens once it has.
 */
export function removeTab(
  config: DashboardViewConfig,
  id: string,
): DashboardViewConfig {
  if (config.tabs.length <= 1 || !config.tabs.some(tab => tab.id === id))
    return config;
  return {
    ...config,
    tabs: config.tabs.filter(tab => tab.id !== id),
    panels: config.panels.filter(
      panel => !isPlainObject(panel) || panelTab(config, panel) !== id,
    ),
  };
}
