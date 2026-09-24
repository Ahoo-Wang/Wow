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
 * Where a way off a board or an embed goes, handed to the host's route: the
 * package never touches the address, so a view opened in the workbench, a
 * follow-up on a group, a panel's custom destination and the way back to
 * the board are all the host's to take. Neither a dashboard's nor a data
 * view's alone, so it sits beside both rather than in either's contract.
 */

import type {
  AnalysisViewConfig,
  DashboardFilters,
  FilterNode,
  FilterTree,
  RecordViewConfig,
  ViewConfig,
} from '../model/index.js';
import { dequal } from 'dequal';
import { drillFilter } from '../analysis/index.js';
import { isSimpleTree } from '../filter/index.js';

/**
 * What a name 「{subject} · {group}」 claims (D20 追问): the conditions that
 * select the group, and what the view is without them. While every one of
 * `conditions` still narrows the view (`narrowsTo`) the name stands; once
 * one is taken off, edited or negated, the view goes by `subject` — a name
 * that still said the group would say what the view no longer shows.
 */
export interface GroupNaming {
  /**
   * What the view is, the group aside: the definition's name for its
   * records, or the name of the question it was opened from.
   */
  subject: string;
  /** The group's own conditions, as the view opened with them. */
  conditions: readonly FilterNode[];
}

/**
 * The board a view was opened from (D26 Q33): what 「返回〈仪表盘〉」 names,
 * and where it goes — the board on the tab and under the filters it was
 * left on. The workbench draws the way back; the host only routes `back`.
 */
export interface BoardOrigin {
  title: string;
  back: DashboardTarget;
}

/**
 * What a view takes with it when it leaves a board (D26 Q30), in its own
 * field names — the same two parts through every way off it:
 * 「在工作台中打开」, a click's view destination, the follow-up menu and a
 * board's own analysis.
 */
export interface HandOver {
  /**
   * What is not the reader's: the board's fixed scope (`fixed`, D26 Q31)
   * and what the page holds — an embed's locked and hidden filters, or an
   * embedded view's `scopeFilter`. The opened view runs under it as its
   * scope — on the applied bar, and nobody's to take off there (H4).
   */
  scopeFilter: FilterTree | null;
  /**
   * What the reader set on the board: the opened view's own conditions,
   * which the reader can take off one by one.
   */
  filter: FilterTree | null;
  /** The board it left, for the way back; absent for a board never saved. */
  from?: BoardOrigin;
}

/**
 * A saved record or analysis view, opened on its own conditions with
 * `filter` added to them — so it opens 「已修改」 and 「还原」 takes them
 * back off — under `scopeFilter` as its scope. A host hands it to
 * `DataWorkbench`'s `handOver` as it is.
 */
export interface SavedViewTarget extends HandOver {
  kind: 'view';
  definitionId: string;
  instanceId: string;
}

/**
 * A view nobody saved: a follow-up on a group — its records, the same
 * question split by another dimension or of the group alone (D22 H) — or
 * an analysis the board owns. What the reader set on the board is already
 * among its conditions, as a view drilled out of another's are; what the
 * page holds is `scopeFilter`. A host hands it to `DataWorkbench`'s
 * `handOver` as it is.
 */
export interface UnsavedViewTarget {
  kind: 'unsaved';
  definitionId: string;
  title: string;
  config: RecordViewConfig | AnalysisViewConfig;
  scopeFilter: FilterTree | null;
  /** What `title` says of a group pressed, when it names one. */
  named?: GroupNaming;
  from?: BoardOrigin;
}

/**
 * A dashboard, opened with `filters` as its reader's values — the host
 * hands them to `DashboardWorkbench`'s `initialFilters` (or
 * `OpenOptions.filters`), never into the board's config. Another board a
 * panel's click opens (D23 Q17: each filter the author mapped holds the
 * group's value, every other one its default), or the way back to the one
 * a view left (`BoardOrigin.back`, on the tab it was left on).
 */
export interface DashboardTarget {
  kind: 'dashboard';
  definitionId: string;
  instanceId: string;
  filters: DashboardFilters;
  /** The tab to open on (`initialTab`); absent, where its reader last read it. */
  tab?: string | null;
}

/** Where a way off the board goes, for the host's route. */
export type ViewNavigation =
  | SavedViewTarget
  | UnsavedViewTarget
  | DashboardTarget
  /** A page of the host's: a panel's URL filled with the group pressed. */
  | { kind: 'url'; url: string };

/** The two a workbench opens (`DataWorkbench`'s `handOver`). */
export type ViewHandOver = SavedViewTarget | UnsavedViewTarget;

/** The conditions of a handed tree, as nodes to AND onto a view's own. */
export function handedConditions(tree: FilterTree | null): FilterNode[] {
  if (!tree) return [];
  return isSimpleTree(tree) ? tree.children : [tree];
}

/**
 * A config with what the reader set on a board (`HandOver.filter`) ANDed
 * onto its own conditions — flattened into one "all of" while its own
 * were one — and read in the editor's advanced mode once they no longer
 * are (`drillFilter`). The config itself when nothing was handed.
 */
export function withHandedFilter<
  C extends RecordViewConfig | AnalysisViewConfig,
>(config: C, filter: FilterTree | null): C {
  const conditions = handedConditions(filter);
  if (conditions.length === 0) return config;
  return withFilterMode({
    ...config,
    filter: drillFilter(config.filter, conditions),
  });
}

/**
 * A condition tree that no longer flattens into one "all of" group is an
 * advanced one, which is how the editor has to open it.
 */
export function withFilterMode<C extends RecordViewConfig | AnalysisViewConfig>(
  config: C,
): C {
  return isSimpleTree(config.filter)
    ? config
    : { ...config, filterMode: 'advanced' };
}

/**
 * Whether a handed view's draft still says what the hand-over left it at
 * (`landed`), whatever object holds it now: what the board added is then
 * the only difference from the saved view, and going back to the board
 * restores it there — nothing is lost, so nothing is asked (D26 Q33). A
 * config is JSON, so equal by value is the same config.
 */
export function untouchedSince(
  draft: ViewConfig,
  landed: ViewConfig | null,
): boolean {
  return landed !== null && (draft === landed || dequal(draft, landed));
}
