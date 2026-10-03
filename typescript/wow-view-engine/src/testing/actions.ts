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

import type { FieldOption, RecordKey } from '../model/index.js';
import {
  actionState,
  choiceOf,
  confirmOf,
  asksFirst,
  initialInput,
  missingInput,
  nextChange,
  offeredAt,
  refusalsByReason,
  runOne,
  splitFor,
  type ActionConfirm,
  type ActionInput,
  type ActionPlace,
  type ActionRefusal,
  type ActionRow as RecordRow,
  type RecordAction,
  type RecordActions,
} from '../runtime/actions.js';

export interface ActionHarnessOptions {
  /** The clock the rules are asked at; `Date.now()` when left out. */
  now?: number;
}

/** One action on one record, as the row, the menu and the detail draw it. */
export interface HarnessState {
  hidden: boolean;
  available: boolean;
  /** Why not, as the host wrote it (a key stays a key); `null` when able. */
  reason: string | null;
}

/** One action over a selection, as the selection's bar and its dialog read it. */
export interface HarnessBulk {
  able: RecordKey[];
  refused: ActionRefusal[];
  /** The refused by reason, the commonest first: what the dialog lists. */
  reasons: { reason: string; count: number; keys: RecordKey[] }[];
}

/** One field of an action's form, as the dialog draws it. */
export interface HarnessField {
  name: string;
  label: string;
  input: 'select' | 'text' | 'number' | 'boolean';
  options: readonly FieldOption[] | null;
  required: boolean;
}

/**
 * A host's declared actions over some records, without a screen
 * (host-integration.md 6): the engine's own reading of them — which place
 * offers which, whether a record takes one now and why not, how a
 * selection splits, what the confirmation and the form ask, when the rules
 * change on their own — so a host's unit test pins its business rules in a
 * line each.
 */
export interface ActionHarness {
  /** Every action's id, in the order declared. */
  readonly ids: readonly string[];
  /** The ids offered at a place, in order. */
  at(place: ActionPlace): string[];
  /**
   * One record's state; with an input, asked as a pressed action is — the
   * form's `initial` under it.
   */
  state(id: string, key: RecordKey, input?: ActionInput): HarnessState;
  bulk(
    id: string,
    keys?: readonly RecordKey[],
    input?: ActionInput,
  ): HarnessBulk;
  /**
   * Whether pressing it at `place` asks before sending — a selection
   * always does — and what it asks: the declared question, or `null` when
   * the engine asks with its own words (a selection with no `confirm`).
   * Asked as the screen asks it: the form's `initial` under `input`.
   */
  asks(
    id: string,
    place: ActionPlace,
    input?: ActionInput,
  ): { asks: boolean; confirm: ActionConfirm | null };
  /** The form's fields, or `null` without one; a choice is one `select`. */
  form(id: string): HarnessField[] | null;
  /** Whether it is a choice: a form of one field of options, offered as them. */
  choice(id: string): readonly FieldOption[] | null;
  /** The required fields `input` leaves blank, the form's `initial` under it. */
  missing(id: string, input: ActionInput): string[];
  /** The soonest time after `now` a record's availability flips on its own. */
  changesAt(key?: RecordKey): number | null;
  /**
   * Sends one record the command as the engine would — with the form's
   * `initial` under `input` — refused with the reason (`ActionRefusedError`)
   * where it does not take it, else the host's `run` itself.
   */
  run(id: string, key: RecordKey, input?: ActionInput): Promise<unknown>;
}

export function actionHarness(
  list: RecordActions,
  rows: readonly RecordRow[],
  { now = Date.now() }: ActionHarnessOptions = {},
): ActionHarness {
  const byKey = new Map(rows.map(row => [row.key, row]));
  const find = (id: string): RecordAction => {
    const action = list.find(each => each.id === id);
    if (!action) throw new Error(`No action "${id}".`);
    return action;
  };
  // What a press starts from: the form's `initial`, the given input over it.
  const pressed = (action: RecordAction, input: ActionInput = {}) => ({
    ...initialInput(action),
    ...input,
  });
  const rowOf = (key: RecordKey): RecordRow => {
    const row = byKey.get(key);
    if (!row) throw new Error(`No record "${String(key)}".`);
    return row;
  };
  return {
    ids: list.map(action => action.id),
    at: place => offeredAt(list, place).map(action => action.id),
    state: (id, key, input) => {
      const action = find(id);
      return actionState(
        action,
        rowOf(key),
        input ? { now, input: pressed(action, input) } : { now },
      );
    },
    bulk(id, keys = rows.map(row => row.key), input) {
      const action = find(id);
      const split = splitFor(
        action,
        keys,
        key => byKey.get(key),
        input ? { now, input: pressed(action, input) } : { now },
      );
      return {
        ...split,
        reasons: refusalsByReason(split.refused).map(({ reason, keys }) => ({
          reason,
          count: keys.length,
          keys,
        })),
      };
    },
    asks(id, place, input) {
      const action = find(id);
      const given = pressed(action, input);
      return {
        asks: asksFirst(action, place, given),
        confirm: confirmOf(action, given),
      };
    },
    form(id) {
      const form = find(id).form;
      if (!form) return null;
      return Object.entries(form).map(([name, field]) => ({
        name,
        label: field.label,
        input: field.options ? 'select' : (field.input ?? 'text'),
        options: field.options ?? null,
        required: field.required !== false,
      }));
    },
    choice: id => choiceOf(find(id))?.options ?? null,
    missing: (id, input) => {
      const action = find(id);
      return missingInput(action, pressed(action, input));
    },
    changesAt: key =>
      nextChange(list, key === undefined ? rows : [rowOf(key)], now),
    run: (id, key, input) => {
      const action = find(id);
      return runOne(action, byKey.get(key), pressed(action, input), now);
    },
  };
}
