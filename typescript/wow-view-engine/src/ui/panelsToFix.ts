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
 * panel's own — the open board's (`['panels', m, …]`), or the same panel
 * as the definition that declares the board at `view` says it
 * (`['views', view, 'config', 'panels', m, …]`) — and `0` when any is the
 * board's own: its filters, its grid, how many panels it has. A finding
 * about another view the definition declares (`['views', n, …]`, `n` not
 * `view`) is about neither: a sibling board's panel 3 is no panel of this
 * one, so it is not counted, nor taken for this board's panel 3, and it
 * does not stop this board running.
 *
 * A panel's error puts out that panel alone and the rest draw (D22 B,
 * todo C), so a board whose only trouble is its panels still runs, and
 * the line over it says so of the panels rather than of the board.
 */
export function panelsToFix(
  issues: readonly Issue[],
  view: number | null = null,
): number {
  const panels = new Set<number>();
  for (const found of issues) {
    if (found.severity !== 'error') continue;
    if (ofAnotherBoard(found, view)) continue;
    const panel =
      found.path[0] === 'panels' && typeof found.path[1] === 'number'
        ? found.path[1]
        : declaredPanel(found.path, view);
    if (panel === null) return 0;
    panels.add(panel);
  }
  return panels.size;
}

/**
 * Whether a definition's finding is about a panel of the board it declares
 * at `view` — which that board, open, reports itself, at `['panels', m, …]`
 * and by the names a reader sees. The status line leaves such an error out
 * of the definition's, so each is said once, named.
 */
export function saidByBoard(found: Issue, view: number | null): boolean {
  return found.severity === 'error' && declaredPanel(found.path, view) !== null;
}

/**
 * Whether a definition's finding is about another view it declares than
 * the board open at `view` (`['views', n, …]`, `n` not `view`; any of them
 * when the board open is not one it declares). On a board it is left to
 * the board it is about, which says it when it is open — its panels by
 * their names, its own the definition's way — so it never heads, nor
 * joins, the line over a board it says nothing of.
 */
export function ofAnotherBoard(found: Issue, view: number | null): boolean {
  return found.path[0] === 'views' && found.path[1] !== view;
}

/**
 * What a board's error line is headed with, over two findings or more
 * (`ErrorStrip`): 「有 2 个面板要先修正才能显示」 when only panels are
 * broken — the rest of the board draws — and 「这个仪表盘要先修正才能运行」
 * when the board itself is. `view` is where the definition declares the
 * board open, if it does (`panelsToFix`).
 */
export function boardErrorTitle(
  issues: readonly Issue[],
  messages: MessageFormatters,
  view: number | null = null,
): string {
  const count = panelsToFix(issues, view);
  return count > 0
    ? messages.label('label.dashboard.panels-need-fixing', { count })
    : messages.label('label.dashboard.needs-fixing');
}

/**
 * The panel of the board declared at `view` a definition's finding is
 * under (`['views', view, 'config', 'panels', m]`), or `null`: another
 * view's, or none.
 */
function declaredPanel(
  path: Issue['path'],
  view: number | null,
): number | null {
  return view !== null &&
    path[0] === 'views' &&
    path[1] === view &&
    path[2] === 'config' &&
    path[3] === 'panels' &&
    typeof path[4] === 'number'
    ? path[4]
    : null;
}
