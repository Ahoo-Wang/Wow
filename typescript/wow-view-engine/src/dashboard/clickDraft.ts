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
 * 「点击时…」 as the author fills it in (D22 I, D23 Q17): every choice the
 * form holds at once — the ones not picked kept, so switching back finds
 * what was typed — and the click they make, or why they make none yet.
 * Pure: the form holds a `ClickDraft` and asks here what it says.
 */

import type {
  BoardValueSource,
  DashboardField,
  DashboardViewConfig,
  FieldDefinition,
  PanelClick,
} from '../model/index.js';
import {
  boardFilterChoices,
  boardValueChoices,
  fillUrl,
  urlPlaceholders,
  type PressableGroup,
} from './click.js';
import { filtersOf } from './filters.js';
import { tabsOf } from './panels.js';

/** The three things a press can do: the follow-up menu, a filter, a destination. */
export type ClickChoice = 'menu' | 'filter' | 'go';

/** Where a destination goes: a saved view, another board, a page. */
export type ClickGoKind = 'view' | 'dashboard' | 'url';

export const CLICK_GO_KINDS: readonly ClickGoKind[] = [
  'view',
  'dashboard',
  'url',
];

/** Everything 「点击时…」 holds, each choice's own part kept while another is picked. */
export interface ClickDraft {
  choice: ClickChoice;
  /** The board filter a press sets, by name. */
  filter: string;
  goKind: ClickGoKind;
  /** The saved view a press opens; `''` while none is picked. */
  view: string;
  /** The page a press opens, as typed. */
  url: string;
  /** The board a press opens; `''` while none is picked. */
  board: string;
  /** What each of that board's filters takes. */
  values: Record<string, BoardValueSource>;
  /** The tab it opens on (D39); `''` for where its reader last read it. */
  tab: string;
}

/**
 * The form as it opens on a panel's stored click. `filters` are the names
 * of the board filters a press on this panel can set: a stored filter no
 * longer among them starts at the first that is.
 */
export function clickDraftOf(
  stored: PanelClick | null,
  filters: readonly string[],
): ClickDraft {
  const blank: ClickDraft = {
    choice: 'go',
    filter: filters[0] ?? '',
    goKind: 'view',
    view: '',
    url: '',
    board: '',
    values: {},
    tab: '',
  };
  if (stored === null) return { ...blank, choice: 'menu' };
  switch (stored.kind) {
    case 'filter':
      return filters.includes(stored.filter)
        ? { ...blank, choice: 'filter', filter: stored.filter }
        : { ...blank, choice: 'filter' };
    case 'view':
      return { ...blank, view: stored.instanceId };
    case 'url':
      return { ...blank, goKind: 'url', url: stored.url };
    case 'dashboard':
      return {
        ...blank,
        goKind: 'dashboard',
        board: stored.instanceId,
        values: stored.values,
        tab: stored.tab ?? '',
      };
  }
}

/**
 * Another board picked for the destination: nothing mapped for the board
 * it replaces carries over by name, since its filters are other filters.
 */
export function withBoard(draft: ClickDraft, board: string): ClickDraft {
  return board === draft.board
    ? draft
    : { ...draft, board, values: {}, tab: '' };
}

/**
 * Whether a URL template fills into a URL a board may open whatever its
 * placeholders hold (`fillUrl`).
 */
export function urlFillable(template: string): boolean {
  return (
    fillUrl(
      template,
      Object.fromEntries(urlPlaceholders(template).map(key => [key, 'x'])),
    ) !== null
  );
}

/** What a mapping can draw on: the panel's dimensions and this board's filters. */
export interface MappingSources {
  groups: readonly PressableGroup[];
  fields: readonly FieldDefinition[] | null;
  own: readonly DashboardField[];
}

/**
 * Every source one of another board's filters can take its value from,
 * this panel's dimensions first (`boardValueChoices`), then this board's
 * filters (`boardFilterChoices`).
 */
export function boardValueSources(
  filter: DashboardField,
  { groups, fields, own }: MappingSources,
): BoardValueSource[] {
  return [
    ...boardValueChoices(filter, groups, fields).map(group => ({
      dimension: group.field,
    })),
    ...boardFilterChoices(filter, own).map(field => ({ filter: field.name })),
  ];
}

/** One source as a key: `dimension:<field>` or `filter:<name>`. */
export function valueSourceKey(source: BoardValueSource): string {
  return 'dimension' in source
    ? `dimension:${source.dimension}`
    : `filter:${source.filter}`;
}

/**
 * What a press takes to the board (D23 Q17), from what the author chose:
 * each of the board's filters mapped to a source that can still give it a
 * value — a dimension of this panel, or a filter of this board's, of a type
 * it takes. Whatever else was stored (a filter gone on either board, a
 * dimension gone) is left out, and named in `stale` by the filter's label.
 * Never guessed by name: a filter the author did not map is not carried.
 */
export function mappedValues(
  board: DashboardViewConfig,
  values: Readonly<Record<string, BoardValueSource>>,
  sources: MappingSources,
): { kept: Record<string, BoardValueSource>; stale: string[] } {
  const byName = new Map(filtersOf(board).map(filter => [filter.name, filter]));
  const kept: Record<string, BoardValueSource> = {};
  const stale: string[] = [];
  for (const [name, source] of Object.entries(values)) {
    const filter = byName.get(name);
    const usable =
      filter &&
      boardValueSources(filter, sources).some(
        choice => valueSourceKey(choice) === valueSourceKey(source),
      );
    if (usable) kept[name] = source;
    else stale.push(filter?.label ?? name);
  }
  return { kept, stale };
}

/**
 * The click a draft makes: `null` for the follow-up menu, `undefined`
 * while the choice picked still lacks what it needs — no filter, no view,
 * no board read yet (`board` is the destination board's config once read),
 * or a URL no board may open. A board's mapping is kept only where it still
 * holds (`mappedValues`).
 */
export function draftedClick(
  draft: ClickDraft,
  board: DashboardViewConfig | undefined,
  sources: MappingSources,
): PanelClick | null | undefined {
  if (draft.choice === 'menu') return null;
  if (draft.choice === 'filter')
    return draft.filter ? { kind: 'filter', filter: draft.filter } : undefined;
  switch (draft.goKind) {
    case 'view':
      return draft.view ? { kind: 'view', instanceId: draft.view } : undefined;
    case 'dashboard':
      return board
        ? {
            kind: 'dashboard',
            instanceId: draft.board,
            values: mappedValues(board, draft.values, sources).kept,
            // Only a tab the board still has (D39): one gone since the
            // click was set is dropped here, as a stale mapping is.
            ...(boardTab(board, draft.tab) ? { tab: draft.tab } : {}),
          }
        : undefined;
    case 'url':
      return urlFillable(draft.url)
        ? { kind: 'url', url: draft.url.trim() }
        : undefined;
  }
}

/**
 * What a destination still lacks, said at its field once 完成 was pressed
 * without it: no view picked, a URL no board may open, no board picked.
 */
export function clickDraftGaps(draft: ClickDraft): {
  view: boolean;
  url: boolean;
  board: boolean;
} {
  const going = draft.choice === 'go';
  return {
    view: going && draft.goKind === 'view' && !draft.view,
    url: going && draft.goKind === 'url' && !urlFillable(draft.url),
    board: going && draft.goKind === 'dashboard' && !draft.board,
  };
}

/**
 * Whether `tab` names one of the board's tabs — the one a press opens it on
 * (D39); `''` names none.
 */
function boardTab(
  board: Pick<DashboardViewConfig, 'tabs'>,
  tab: string | undefined,
): boolean {
  return !!tab && tabsOf(board).some(entry => entry.id === tab);
}
