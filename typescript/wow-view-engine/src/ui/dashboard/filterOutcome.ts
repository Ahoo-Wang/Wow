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

import { useEffect, useRef } from 'react';
import type { DashboardFilters } from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import { useViewMessages } from '../kit/MessagesProvider.js';

/** What a change of the filters waits to say, until its panels settle. */
interface Pending {
  /** The filters whose value changed, by name, in the bar's order. */
  names: readonly string[];
  /** Whether every one of them was cleared rather than set. */
  cleared: boolean;
  /**
   * Whether a panel has been seen loading since. The board shows a value at
   * once and runs it a moment later (`FilterValues.commit`), so the panels
   * standing idle right after a change have not run it yet: the outcome
   * waits for them to start and then to stop.
   */
  ran: boolean;
}

/**
 * Says what a change of the board's filters came to, once it has (WCAG
 * 4.1.3).
 *
 * A board's panels rerun each on its own when a filter changes, and a
 * reader heard nothing of it: the value in the control changed under
 * their cursor, and the eight panels it narrowed changed where they were
 * not looking — one of them perhaps into a failure. So when every panel
 * has settled, the board's one voice says it once: which filters, how many
 * panels they reached, and how many of those could not load
 * (「已按仓库筛选，5 个面板已更新。1 个面板没能加载。」).
 *
 * The change is read off the values the board holds, not off the control
 * that changed them, so a value typed, picked, cleared or pressed on a
 * panel (a cross-filter) is said the same way. A board opened, or another
 * board put in its place, says nothing: nothing was changed by the reader.
 */
export function useFilterOutcome(
  dashboard: DashboardController,
  say: (words: string) => void,
): void {
  const messages = useViewMessages();
  const values = dashboard.filters.values;
  const fields = dashboard.filterFields;
  const { loading, panels, tab } = dashboard;
  const seen = useRef<{ values: DashboardFilters['values']; fields: unknown }>({
    values,
    fields,
  });
  const pending = useRef<Pending | null>(null);

  useEffect(() => {
    const was = seen.current;
    if (was.values !== values || was.fields !== fields) {
      seen.current = { values, fields };
      // Another board, or the same one rebuilt: nothing the reader changed.
      if (was.fields === fields) {
        const changed = fields
          .map(field => field.name)
          .filter(
            name =>
              JSON.stringify(was.values[name] ?? null) !==
              JSON.stringify(values[name] ?? null),
          );
        const all = new Set([...(pending.current?.names ?? []), ...changed]);
        if (all.size > 0)
          pending.current = {
            names: fields
              .map(field => field.name)
              .filter(name => all.has(name)),
            cleared: [...all].every(name => values[name] === undefined),
            ran: false,
          };
      }
    }
    const waiting = pending.current;
    if (!waiting) return;
    const reached = panels.filter(
      panel =>
        panel.tab === tab &&
        panel.runtime !== null &&
        waiting.names.some(name => panel.reach[name]?.wired === true),
    );
    if (loading) {
      waiting.ran = true;
      return;
    }
    // A change no panel reads runs nothing, and is said at once.
    if (!waiting.ran && reached.length > 0) return;
    pending.current = null;
    const failed = reached.filter(failedNow).length;
    const said = messages.label(
      waiting.cleared
        ? 'label.filters.outcome-cleared'
        : 'label.filters.outcome',
      {
        filters: waiting.names
          .map(name => fields.find(field => field.name === name)?.label ?? name)
          .map(label => messages.say(label))
          .join(messages.label('label.filter.join')),
        count: reached.length - failed,
      },
    );
    say(
      failed > 0
        ? `${said}${messages.label('label.filters.outcome-failed', { count: failed })}`
        : said,
    );
  }, [values, fields, loading, panels, tab, messages, say]);
}

/** Whether a panel's last query failed and it has nothing to show for it. */
function failedNow(panel: DashboardPanelView): boolean {
  return panel.runtime?.getSnapshot().query.status === 'error';
}
