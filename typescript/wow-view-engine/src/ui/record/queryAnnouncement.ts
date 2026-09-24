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
import type { RecordTableController } from '../../react/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/**
 * What a query says about itself, or nothing at all.
 *
 * A record view answers with rows, and rows are the one thing a reader who
 * is not looking at the table never learns about: the toolbar, the pagination
 * and the empty state all change under a reader who is told none of it. The
 * sentences are the ones already on screen — the pagination's count and the
 * empty result's title — because a second wording for the same fact is a
 * second fact to reconcile.
 *
 * A failure says nothing here. `QueryStrip` draws it as an `error` status
 * strip, which is `role="alert"` and interrupts on its own; saying it twice
 * is the reader hearing the same failure from two directions.
 */
export function querySentence(
  table: RecordTableController,
  messages: MessageFormatters,
  /**
   * The empty result's title as the screen is drawing it — a host may put
   * the case in its own words ("no orders are waiting"). Said as heard,
   * because the two are one sentence and not two.
   */
  emptyTitle?: string,
): string | null {
  if (table.loading) return messages.label('label.status.querying');
  if (!table.hasResult || table.status === 'error') return null;
  if (table.rows.length === 0)
    return emptyTitle ?? messages.label('label.record.empty');
  // The number the reader asked about when the source gives one, and the
  // number that did arrive when it does not — the rule the pagination bar
  // reads by, so the bar and the announcement never disagree.
  const total = table.paging?.mode === 'paged' ? table.paging.total : undefined;
  return total === undefined
    ? messages.label('label.pagination.on-page', { count: table.rows.length })
    : messages.label('label.pagination.total', { total });
}

/**
 * Says each query out loud once: that one is running, and what came back.
 *
 * The sentence is derived rather than pushed from the controller, so a
 * re-render on any other account cannot repeat it — the effect speaks only
 * when the sentence itself is new. Which is also what makes a result that
 * matches the previous one exactly say nothing a second time: the
 * "running" sentence sits between two landings, so every query the reader
 * waited through is read back, and a render that changed nothing is not.
 */
export function useQueryAnnouncement(
  table: RecordTableController,
  messages: MessageFormatters,
  say: (message: string) => void,
  /** What the empty result is titled on screen, when the host titles it. */
  emptyTitle?: string,
): void {
  const sentence = querySentence(table, messages, emptyTitle);
  const said = useRef<string | null>(null);
  useEffect(() => {
    if (sentence === null || sentence === said.current) return;
    said.current = sentence;
    say(sentence);
  }, [say, sentence]);
}
