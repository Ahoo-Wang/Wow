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

import { dequal } from 'dequal';
import { overlaid, type DashboardViewConfig } from '../../model/index.js';

/**
 * The edits building a board is made of, by the command that makes each —
 * `DashboardEditing` and `DashboardFilterEditing` less the undoing itself.
 */
export type EditCommand =
  | 'addPanel'
  | 'removePanel'
  | 'duplicatePanel'
  | 'renamePanel'
  | 'replacePanelView'
  | 'editPanelContent'
  | 'movePanelToTab'
  | 'setPresentation'
  | 'referToSaved'
  | 'setPanelClick'
  | 'place'
  | 'reorderPanel'
  | 'addTab'
  | 'renameTab'
  | 'moveTab'
  | 'removeTab'
  | 'addFilter'
  | 'renameFilter'
  | 'retypeFilter'
  | 'removeFilter'
  | 'setFilterDefault'
  | 'setFilterRequired'
  | 'setFilterMultiple'
  | 'setFilterOptions'
  | 'moveFilter'
  | 'bindPanel'
  | 'unbindPanels'
  | 'setTimeGrouping';

/**
 * One step of the board's history: the command, and what it was about — a
 * panel's id, a tab's id or a filter's name; `null` for the time grouping.
 * A screen names the step by it (「撤销：移除「北区订单」」).
 */
export interface EditStep {
  command: EditCommand;
  subject: string | null;
}

/** What the history offers now: the step an undo would take back, and a redo. */
export interface EditHistoryState {
  undo: EditStep | null;
  redo: EditStep | null;
}

export const NO_HISTORY: EditHistoryState = { undo: null, redo: null };

/**
 * The members of one config a step changed, as they were and as they came
 * out; a member a config lacks is `undefined` (configs are JSON, which has
 * no `undefined` of its own).
 */
interface Change {
  before: Partial<DashboardViewConfig>;
  after: Partial<DashboardViewConfig>;
}

/** One step, over both configs an edit writes: the draft and the screen. */
interface Entry {
  step: EditStep;
  draft: Change;
  applied: Change;
}

/**
 * Commands a burst of which is one step: the same command on the same thing
 * again and again is someone typing a name, picking a chart's options or
 * trying defaults, and an undo that took one keystroke back would be no
 * undo at all. Anything else in between ends the burst.
 */
const BURSTS: ReadonlySet<EditCommand> = new Set([
  'renamePanel',
  'renameTab',
  'renameFilter',
  'setFilterDefault',
  'setFilterOptions',
  'setPresentation',
  'setTimeGrouping',
]);

/**
 * The members of a board the building commands write — each command one
 * step of the history, which keeps these members as they were and came out
 * (A-10). A plain `edit` of the board leaves them to those commands.
 */
const BUILT_MEMBERS = [
  'panels',
  'tabs',
  'fields',
  'timeGrouping',
] as const satisfies readonly (keyof DashboardViewConfig)[];

/**
 * A patch with the members the building commands own taken out: an undo
 * puts back the whole member a step changed, so a list an `edit` had
 * changed underneath it would be put back too, and silently — the edit
 * noted nowhere, the undo naming another step.
 */
export function outsideHistory(
  patch: Partial<DashboardViewConfig>,
): Partial<DashboardViewConfig> {
  const rest = { ...patch };
  for (const member of BUILT_MEMBERS) delete rest[member];
  return rest;
}

/** How many steps back an undo reaches. */
export const EDIT_HISTORY_DEPTH = 100;

/**
 * The board's history while it is built: one step per edit command, each
 * holding only the members of the config it changed, as they were and as
 * they came out — so an undo puts back what that step changed and nothing
 * else, and a global condition composed or a refresh interval set in
 * between stays as it is.
 *
 * A redo is the step an undo took back, until the next edit.
 */
export class EditHistory {
  private done: Entry[] = [];
  private undone: Entry[] = [];

  get state(): EditHistoryState {
    const undo = this.done[this.done.length - 1]?.step ?? null;
    const redo = this.undone[this.undone.length - 1]?.step ?? null;
    return undo === null && redo === null ? NO_HISTORY : { undo, redo };
  }

  /**
   * Notes one edit: the configs before it and after it. A step that changed
   * nothing is not one; a burst of one command on one thing is one step,
   * and a burst that came back to where it began is none.
   */
  record(
    step: EditStep,
    draft: [DashboardViewConfig, DashboardViewConfig],
    applied: [DashboardViewConfig, DashboardViewConfig],
  ): void {
    const change = { draft: diff(...draft), applied: diff(...applied) };
    if (isEmpty(change.draft) && isEmpty(change.applied)) return;
    this.undone = [];
    const last = this.done[this.done.length - 1];
    if (
      last &&
      BURSTS.has(step.command) &&
      last.step.command === step.command &&
      last.step.subject === step.subject
    ) {
      last.draft = merged(last.draft, change.draft);
      last.applied = merged(last.applied, change.applied);
      if (isEmpty(last.draft) && isEmpty(last.applied)) this.done.pop();
      return;
    }
    this.done.push({ step, ...change });
    if (this.done.length > EDIT_HISTORY_DEPTH) this.done.shift();
  }

  /**
   * Takes the last step back: the members it changed, as they were, to lay
   * over the draft and over the screen; `null` when there is none.
   */
  undo(): Rewind | null {
    const entry = this.done.pop();
    if (!entry) return null;
    this.undone.push(entry);
    return {
      step: entry.step,
      draft: entry.draft.before,
      applied: entry.applied.before,
    };
  }

  /** Makes the last step taken back again; `null` when there is none. */
  redo(): Rewind | null {
    const entry = this.undone.pop();
    if (!entry) return null;
    this.done.push(entry);
    return {
      step: entry.step,
      draft: entry.draft.after,
      applied: entry.applied.after,
    };
  }

  /** Starts again: after a revert, a save, or a board read anew. */
  clear(): void {
    this.done = [];
    this.undone = [];
  }
}

/** What an undo or a redo lays over the two configs. */
export interface Rewind {
  step: EditStep;
  draft: Partial<DashboardViewConfig>;
  applied: Partial<DashboardViewConfig>;
}

/**
 * A config with these members laid over it — one `undefined` taken out, as
 * the config it came from did not have it — or the same config when that
 * changes nothing.
 */
export function rewound(
  config: DashboardViewConfig,
  members: Partial<DashboardViewConfig>,
): DashboardViewConfig {
  const keys = Object.keys(members) as (keyof DashboardViewConfig)[];
  return keys.every(key => config[key] === members[key])
    ? config
    : overlaid(config, members);
}

/**
 * The members one edit changed. An edit hands back what it did not touch as
 * it was; a member made anew the same — a form submitted unchanged — is no
 * change either.
 */
function diff(before: DashboardViewConfig, after: DashboardViewConfig): Change {
  const change: Change = { before: {}, after: {} };
  if (before === after) return change;
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  for (const key of keys as Set<keyof DashboardViewConfig>)
    if (before[key] !== after[key] && !dequal(before[key], after[key])) {
      set(change.before, key, before[key]);
      set(change.after, key, after[key]);
    }
  return change;
}

/**
 * Two changes one after the other as one: the first's `before`, the
 * second's `after`, and a member that came back to where it began dropped.
 */
function merged(first: Change, then: Change): Change {
  const out: Change = {
    before: { ...first.before },
    after: { ...first.after },
  };
  for (const key of Object.keys(then.after) as (keyof DashboardViewConfig)[]) {
    if (!(key in out.before)) set(out.before, key, then.before[key]);
    set(out.after, key, then.after[key]);
  }
  for (const key of Object.keys(out.after) as (keyof DashboardViewConfig)[])
    if (dequal(out.before[key], out.after[key])) {
      delete out.before[key];
      delete out.after[key];
    }
  return out;
}

function isEmpty(change: Change): boolean {
  return Object.keys(change.after).length === 0;
}

function set(
  members: Partial<DashboardViewConfig>,
  key: keyof DashboardViewConfig,
  value: unknown,
): void {
  (members as Record<string, unknown>)[key] = value;
}
