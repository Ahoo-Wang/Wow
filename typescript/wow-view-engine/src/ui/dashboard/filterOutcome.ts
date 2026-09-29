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
import {
  useViewMessages,
  type MessageFormatters,
} from '../kit/MessagesProvider.js';

/** What a change of the filters waits to say, until its panels settle. */
interface Pending {
  /** The filters whose value changed, by name, in the bar's order. */
  names: readonly string[];
  /** Whether every one of them was cleared rather than set. */
  cleared: boolean;
  /**
   * The board's `filtersRun` when the change was made. The board shows a
   * value at once and runs it a moment later (`FilterValues.commit`), so
   * nothing the panels do before that step is the change's doing — an
   * auto refresh that happens to be out, say.
   */
  since: number;
  /**
   * The panels the change set running, taken at the step it went out;
   * `null` until then. The sentence waits for these and no others, and
   * counts their failures and no others.
   */
  running: ReadonlySet<string> | null;
}

/**
 * Says what a change of the board's filters came to, once it has (WCAG
 * 4.1.3).
 *
 * A board's panels rerun each on its own when a filter changes, and a
 * reader heard nothing of it: the value in the control changed under
 * their cursor, and the eight panels it narrowed changed where they were
 * not looking — one of them perhaps into a failure. So when the panels the
 * change set running have settled, the board's one voice says it once:
 * which filters, how many panels they reached, and how many of those could
 * not load (「已按仓库筛选，5 个面板已更新。1 个面板没能加载。」).
 *
 * The change is read off the values the board holds, not off the control
 * that changed them, so a value typed, picked, cleared or pressed on a
 * panel (a cross-filter) is said the same way. What it ran is read off the
 * step at which the board sent the values out (`filtersRun`): the panels
 * running right after it are the ones it asked. A change that ran nothing
 * — undone within the moment, or refused by every panel — says nothing. A
 * board opened, or another board put in its place, says nothing either:
 * nothing was changed by the reader.
 */
export function useFilterOutcome(
  dashboard: DashboardController,
  say: (words: string) => void,
): void {
  const messages = useViewMessages();
  const values = dashboard.filters.values;
  const fields = dashboard.filterFields;
  const { loading, panels, tab, filtersRun } = dashboard;
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
      if (was.fields !== fields) pending.current = null;
      else {
        const changed = fields
          .map(field => field.name)
          .filter(
            name =>
              JSON.stringify(was.values[name] ?? null) !==
              JSON.stringify(values[name] ?? null),
          );
        const all = new Set([...(pending.current?.names ?? []), ...changed]);
        if (changed.length > 0)
          pending.current = {
            names: fields
              .map(field => field.name)
              .filter(name => all.has(name)),
            cleared: [...all].every(name => holdsNothing(values[name])),
            since: filtersRun,
            running: null,
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
    // A change no panel reads runs nothing, and is said at once.
    if (reached.length === 0) {
      pending.current = null;
      say(sentence(messages, fields, waiting, 0, 0));
      return;
    }
    if (waiting.running === null) {
      // Not sent out yet.
      if (filtersRun === waiting.since) return;
      const running = new Set(reached.filter(isLoading).map(panel => panel.id));
      // Sent out, and nothing ran: nothing to say.
      if (running.size === 0) {
        pending.current = null;
        return;
      }
      waiting.running = running;
    }
    const asked = reached.filter(panel => waiting.running!.has(panel.id));
    if (asked.some(isLoading)) return;
    pending.current = null;
    const failed = asked.filter(failedNow).length;
    say(sentence(messages, fields, waiting, asked.length - failed, failed));
  }, [values, fields, loading, panels, tab, filtersRun, messages, say]);
}

/** What a change came to, in the board's words. */
function sentence(
  messages: MessageFormatters,
  fields: DashboardController['filterFields'],
  of: Pending,
  count: number,
  failed: number,
): string {
  const said = messages.label(
    of.cleared ? 'label.filters.outcome-cleared' : 'label.filters.outcome',
    {
      filters: of.names
        .map(name => fields.find(field => field.name === name)?.label ?? name)
        .map(label => messages.say(label))
        .join(messages.label('label.filter.join')),
      count,
    },
  );
  return failed > 0
    ? `${said}${messages.label('label.filters.outcome-failed', { count: failed })}`
    : said;
}

/** Whether a filter's value holds nothing: unset, `null` or an empty list. */
function holdsNothing(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    (Array.isArray(value) && value.length === 0)
  );
}

/** Whether a panel's query is out. */
function isLoading(panel: DashboardPanelView): boolean {
  return panel.runtime?.getSnapshot().query.status === 'loading';
}

/** Whether a panel's last query failed and it has nothing to show for it. */
function failedNow(panel: DashboardPanelView): boolean {
  return panel.runtime?.getSnapshot().query.status === 'error';
}
