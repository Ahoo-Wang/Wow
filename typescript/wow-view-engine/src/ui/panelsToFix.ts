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

import type { Issue } from '../model/index.js';
import type { MessageFormatters } from './MessagesProvider.js';

/**
 * How many panels these errors are about, when every one of them is a
 * panel's own — a board's (`['panels', m, …]`) or a board a definition
 * declares (`['views', n, 'config', 'panels', m, …]`) — and `0` when any
 * is the board's own: its filters, its grid, how many panels it has.
 *
 * A panel's error puts out that panel alone and the rest draw (D22 B,
 * todo C), so a board whose only trouble is its panels still runs, and
 * the line over it says so of the panels rather than of the board.
 */
export function panelsToFix(issues: readonly Issue[]): number {
  const panels = new Set<number>();
  for (const found of issues) {
    if (found.severity !== 'error') continue;
    const panel = panelOf(found.path);
    if (panel === null) return 0;
    panels.add(panel);
  }
  return panels.size;
}

/**
 * What a board's error line is headed with, over two findings or more
 * (`ErrorStrip`): 「有 2 个面板要先修正才能显示」 when only panels are
 * broken — the rest of the board draws — and 「这个仪表盘要先修正才能运行」
 * when the board itself is.
 */
export function boardErrorTitle(
  issues: readonly Issue[],
  messages: MessageFormatters,
): string {
  const count = panelsToFix(issues);
  return count > 0
    ? messages.label('label.dashboard.panels-need-fixing', { count })
    : messages.label('label.dashboard.needs-fixing');
}

/**
 * The panel a finding's path is under, or `null`. By its place on the
 * board: the board open reports its panel at `['panels', m]` and the
 * definition that declares it the same panel at `['views', n, 'config',
 * 'panels', m]`, and the two are one panel to fix.
 */
function panelOf(path: Issue['path']): number | null {
  if (path[0] === 'panels' && typeof path[1] === 'number') return path[1];
  if (
    path[0] === 'views' &&
    path[2] === 'config' &&
    path[3] === 'panels' &&
    typeof path[4] === 'number'
  )
    return path[4];
  return null;
}
