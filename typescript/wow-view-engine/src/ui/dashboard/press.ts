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

import type {
  DataViewConfig,
  PanelClick,
  RecordData,
} from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import type {
  CrossFilterOutcome,
  HandOver,
  ViewNavigation,
  PressDestination,
  ViewRuntime,
} from '../../runtime/index.js';
import { describeFilter } from '../../filter/index.js';
import type { DisplayContext } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { summaryText } from '../summary.js';

/**
 * What a press on one group of a panel can do, as the grid hands it to the
 * panel (D22 H, I): its click, the host's route, and the board's commands
 * on this panel's groups.
 */
export interface PanelPress {
  click: PanelClick | null;
  /** The host's route; the follow-up menu and a destination need it. */
  navigate?(to: ViewNavigation): void;
  crossFilter(row: RecordData): CrossFilterOutcome;
  pressed(row: RecordData): boolean;
  destination(row: RecordData): Promise<PressDestination | null>;
  /** What the panel's view takes off the board (D26 Q30), read at the press. */
  handOver(): HandOver | null;
  /** Says one line to whoever cannot see what a press changed. */
  say(text: string): void;
}

/** One panel's press, over the board's commands. */
export function panelPress(
  panel: DashboardPanelView,
  dashboard: DashboardController,
  navigate: ((to: ViewNavigation) => void) | undefined,
  say: (text: string) => void,
): PanelPress {
  return {
    click: panel.click,
    ...(navigate ? { navigate } : {}),
    crossFilter: row => dashboard.crossFilter(panel.id, row),
    pressed: row => dashboard.pressed(panel.id, row),
    destination: row => dashboard.destination(panel.id, row),
    handOver: () => dashboard.handOver(panel.id),
    say,
  };
}

/**
 * What a press does on this panel, or `null` for nothing: a click that sets
 * a filter works on any page; the follow-up menu and a destination go away
 * from the board, so they are there only with a route to go by (D22 H:
 * 「宿主没给路由钩子时，不出追问菜单」).
 */
export type PressMode = 'menu' | 'filter' | 'go';

export function pressMode(press: PanelPress | undefined): PressMode | null {
  if (!press) return null;
  if (press.click?.kind === 'filter') return 'filter';
  if (!press.navigate) return null;
  return press.click ? 'go' : 'menu';
}

/**
 * The board's filters as they reach the panel, in the applied bar's words,
 * one after another — what the follow-up menu says the group is read under
 * (D22 H, 「仓库 是 华南 · 本月」); `undefined` when none reaches it.
 */
export function boardContext(
  runtime: ViewRuntime<DataViewConfig>,
  messages: MessageFormatters,
  display: DisplayContext,
): string | undefined {
  const scope = runtime.scopeFilter;
  if (!scope) return undefined;
  const items = describeFilter(runtime.fields, scope, runtime.kinds);
  if (items.length === 0) return undefined;
  return messages.label('label.drill.board', {
    conditions: items
      .map(item => summaryText(item, messages, display))
      .join(' · '),
  });
}

/**
 * What a press that set a filter says it did. A set says which group it
 * set the filter to (`group`, as the follow-up menu heads it): a press on
 * another group is another sentence, so it is read out again (U-05) — the
 * same words twice are one announcement.
 */
export function crossFilterSaid(
  outcome: CrossFilterOutcome,
  messages: MessageFormatters,
  group = '',
): string | null {
  if (outcome.kind === 'none') return null;
  const filter = outcome.filter.label;
  switch (outcome.kind) {
    case 'set':
      return group === ''
        ? messages.label('label.click.filtered', { filter })
        : messages.label('label.click.filtered-to', { filter, group });
    case 'cleared':
      return messages.label('label.click.cleared', { filter });
    case 'no-value':
      return messages.label('label.click.no-value', { filter });
  }
}
