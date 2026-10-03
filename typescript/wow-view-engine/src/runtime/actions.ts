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

import { text, type FieldOption, type RecordKey } from '../model/index.js';
import type { RecordRow } from '../record/index.js';

/**
 * Declared actions (host-integration.md 5, D67): the host says **what** —
 * which commands a record takes, when, what a refusal says, whether to ask
 * first and what to ask for — and the engine decides **where and how**:
 * the row's inline button and its overflow menu, the selection's bar, the
 * record detail, the confirmation and the form, a selection partly able to
 * take a command, the run a few at a time with its progress, its stop and
 * its outcome, and the refresh after.
 *
 * Everything here is headless: the declarations, and the pure reading of
 * them the UI and `/testing`'s `actionHarness` share, so a host's unit test
 * asks the very rules the screen draws by.
 */

/** A record as an action is handed it: its key and its data. */
export type ActionRow = RecordRow;

/** Where a declared action is offered. */
export type ActionPlace = 'row' | 'bulk' | 'detail';

/** Every place, in order: what an action that names none is offered at. */
const ACTION_PLACES: readonly ActionPlace[] = ['row', 'bulk', 'detail'];

/** How much an action costs: `danger` draws it (and its confirmation) red. */
export type ActionTone = 'default' | 'danger';

/** What a form (or a choice) gave, by field name. */
export type ActionInput = Readonly<Record<string, unknown>>;

/**
 * What an action's rules are asked with: the time they are asked at — the
 * engine's clock, a harness's own in a test — and, where one is known, the
 * input the command would run with (a choice's option, a filled form).
 */
export interface ActionContext {
  readonly now: number;
  readonly input?: ActionInput;
}

/** `true`, or why not — a key (`text(…)`) or words, said where shown. */
export type Availability = true | string;

/**
 * One field of an action's form, drawn by the condition editor's own value
 * controls (`FilterValueEditor`): a list of options, a number, a yes/no or
 * a line of text.
 */
export interface ActionFormField {
  label: string;
  /** Options to pick one of; the field is then a choice. */
  options?: readonly FieldOption[];
  /** Without options: what is typed. `text` by default. */
  input?: 'text' | 'number' | 'boolean';
  /** Whether the command can run without it; `true` by default. */
  required?: boolean;
  /** What the form opens with. */
  initial?: string | number | boolean;
}

/** An action's form, by field name: what `run` receives as its input. */
export type ActionForm = Readonly<Record<string, ActionFormField>>;

/**
 * The question an action asks before it runs. Words are keys or text;
 * `{count}` is how many records it is for (with a `-one` form where a
 * language says one apart), `{value}` the option a choice picked.
 */
export interface ActionConfirm {
  title: string;
  body?: string;
  /**
   * The confirming button, and the run's name on the outcome line; the
   * action's label by default.
   */
  action?: string;
  /** The action's tone by default. */
  tone?: ActionTone;
  /**
   * `always` (the default): one record asks too. `bulk`: only a selection
   * asks — a command safe for one record that the reader should count
   * before it goes to many. A selection always asks either way.
   */
  ask?: 'always' | 'bulk';
}

/** One declared command on a record. */
export interface RecordAction {
  /** Unique among the actions of one binding. */
  readonly id: string;
  readonly label: string;
  /** The one a reader presses most: a button in the row, not in the menu. */
  readonly primary?: boolean;
  readonly tone?: ActionTone;
  /** Where it is offered; every place by default. */
  readonly on?: readonly ActionPlace[];
  /**
   * Whether this record does not show the action at all — the reader may
   * not do it — as against `available`, which shows it disabled with why.
   */
  hidden?(row: RecordRow, context: ActionContext): boolean;
  /**
   * `true` when the record takes the command now, else why not. Asked with
   * the input where one is known, so a choice can refuse one option (the
   * value the record already holds) and offer the rest.
   */
  available?(row: RecordRow, context: ActionContext): Availability;
  /**
   * When `available` next changes on its own — a prepared execution becomes
   * preparable once it times out — as a time in milliseconds; nothing when
   * only a new state changes it. The engine asks again then, so the host
   * keeps no timer.
   */
  changesAt?(row: RecordRow, context: ActionContext): number | null | undefined;
  /**
   * Ask first; a function of the input, where the words depend on it. Its
   * words are said with `{count}` (how many records), `{value}` (a choice's
   * option) and `{record}` (the record's key, when it is one record).
   */
  readonly confirm?: ActionConfirm | ((input: ActionInput) => ActionConfirm);
  /**
   * What the command needs besides the record. A form of one field with
   * options is a choice: its options are offered in the menu, and the one
   * picked is the input.
   */
  readonly form?: ActionForm;
  /**
   * Sends the command for one record. It resolves once the read model
   * reflects it — a Wow command waits for `CommandStage.SNAPSHOT` (or the
   * stage the host's projection needs) — because the engine reads the view
   * again right after, and a refresh that ran ahead of the command would
   * show the old state. It throws when the command was refused; what it
   * throws is read for the source's own reason.
   *
   * A command is a write, so make it idempotent — a request id or an
   * idempotency key the service deduplicates by: a `run` that times out or
   * loses the network after sending has an outcome nobody knows, and the
   * reader, told to check, may press again.
   */
  run(row: RecordRow, input: ActionInput): Promise<unknown>;
  /**
   * How long one record's `run` is waited for, in milliseconds. Past it the
   * engine stops waiting and reports the record's outcome as unknown — it
   * may have taken the command — rather than holding the surface busy.
   * No deadline by default.
   */
  readonly timeout?: number;
}

/** A binding's declared actions, as `actions()` hands them back. */
export type RecordActions = readonly RecordAction[];

/**
 * A binding's actions, checked: every one has an id no other has, a label
 * and a `run`. A mistake here is the host's code, so it throws at once.
 */
export function actions(list: readonly RecordAction[]): RecordActions {
  const ids = new Set<string>();
  for (const action of list) {
    if (typeof action.id !== 'string' || action.id === '')
      throw new Error('An action needs an id.');
    if (ids.has(action.id))
      throw new Error(`Two actions share the id "${action.id}".`);
    if (typeof action.run !== 'function')
      throw new Error(`Action "${action.id}" has no run.`);
    ids.add(action.id);
  }
  return Object.freeze([...list]);
}

/** The actions offered at `place`, in the order declared. */
export function offeredAt(
  list: RecordActions | undefined,
  place: ActionPlace,
): RecordAction[] {
  return (list ?? []).filter(action =>
    (action.on ?? ACTION_PLACES).includes(place),
  );
}

/** Why a record the engine does not hold is not sent: it is not on screen. */
const UNSEEN = text('label.action.unseen');
/** Why a record the action is hidden on is not sent. */
const NOT_OFFERED = text('label.action.not-offered');
/** What a refusal without words of the host's own says. */
export const UNAVAILABLE = text('label.action.unavailable');
/** What a failure whose error carries no words says. */
export const FAILED = text('label.action.failed');
/** Why a record's outcome is unknown: its `run` outlasted the action's `timeout`. */
export const TIMED_OUT = text('label.action.timed-out');
/** Why a record's outcome is unknown: the reader stopped waiting for it. */
export const ABANDONED = text('label.action.abandoned');

/** One action on one record: hidden, or available, or refused and why. */
interface ActionState {
  hidden: boolean;
  available: boolean;
  /** Why not, as the host said it; `null` while it is available. */
  reason: string | null;
}

/**
 * What one action says about one record now. A rule that throws is the
 * host's bug, and it refuses rather than offering a command nobody checked.
 */
export function actionState(
  action: RecordAction,
  row: RecordRow,
  context: ActionContext,
): ActionState {
  try {
    if (action.hidden?.(row, context) === true)
      return { hidden: true, available: false, reason: NOT_OFFERED };
    const said: unknown = action.available?.(row, context) ?? true;
    if (said === true) return { hidden: false, available: true, reason: null };
    return {
      hidden: false,
      available: false,
      reason: typeof said === 'string' && said !== '' ? said : UNAVAILABLE,
    };
  } catch {
    return { hidden: false, available: false, reason: UNAVAILABLE };
  }
}

/** A form that is one field of options: offered as its options. */
interface ActionChoice {
  name: string;
  field: ActionFormField;
  options: readonly FieldOption[];
}

/** The choice an action's form is, or `null` for a form to fill (or none). */
export function choiceOf(action: RecordAction): ActionChoice | null {
  const entries = Object.entries(action.form ?? {});
  if (entries.length !== 1) return null;
  const [name, field] = entries[0];
  const options = field.options;
  return options && options.length > 0 ? { name, field, options } : null;
}

/** What a form opens with: each field's `initial`, where it has one. */
export function initialInput(action: RecordAction): ActionInput {
  const input: Record<string, unknown> = {};
  for (const [name, field] of Object.entries(action.form ?? {}))
    if (field.initial !== undefined) input[name] = field.initial;
  return input;
}

/** Whether a value says nothing yet. */
function blank(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === 'string' && value.trim() === '') ||
    (typeof value === 'number' && !Number.isFinite(value)) ||
    (Array.isArray(value) && value.length === 0)
  );
}

/** The required fields `input` leaves blank, by name, in the form's order. */
export function missingInput(
  action: RecordAction,
  input: ActionInput,
): string[] {
  return Object.entries(action.form ?? {})
    .filter(([name, field]) => field.required !== false && blank(input[name]))
    .map(([name]) => name);
}

/**
 * The question an action asks with this input, or `null`. A question the
 * host computes and that throws is the host's bug: the action is asked by
 * its name rather than sent unasked or taking the surface down.
 */
export function confirmOf(
  action: RecordAction,
  input: ActionInput,
): ActionConfirm | null {
  const confirm = action.confirm;
  if (!confirm) return null;
  if (typeof confirm !== 'function') return confirm;
  try {
    const asked: unknown = confirm(input);
    if (
      typeof asked === 'object' &&
      asked !== null &&
      typeof (asked as ActionConfirm).title === 'string'
    )
      return asked as ActionConfirm;
  } catch {
    // Asked by name, below.
  }
  return { title: action.label };
}

/**
 * Whether pressing the action at `place` opens a dialog before anything is
 * sent: a selection always does (how many, and which will not take it), a
 * form to fill does, and a record's own press does where the action asks
 * first. A choice's option is its input, so a choice alone asks nothing.
 */
export function asksFirst(
  action: RecordAction,
  place: ActionPlace,
  input: ActionInput,
): boolean {
  if (place === 'bulk') return true;
  if (action.form && !choiceOf(action)) return true;
  const confirm = confirmOf(action, input);
  return confirm !== null && confirm.ask !== 'bulk';
}

/** One record the command will not be sent to, and why. */
export interface ActionRefusal {
  key: RecordKey;
  reason: string;
}

/** A selection split by what one action says of each record. */
interface ActionSplit {
  /** The records that take it, in the order given. */
  able: RecordKey[];
  refused: ActionRefusal[];
}

/**
 * `keys` split by whether each takes the action now — a record the engine
 * does not hold is refused (`UNSEEN`), since its state is not known and
 * `run` is handed the record.
 */
export function splitFor(
  action: RecordAction,
  keys: readonly RecordKey[],
  rowOf: (key: RecordKey) => RecordRow | undefined,
  context: ActionContext,
): ActionSplit {
  const able: RecordKey[] = [];
  const refused: ActionRefusal[] = [];
  for (const key of keys) {
    const row = rowOf(key);
    if (!row) {
      refused.push({ key, reason: UNSEEN });
      continue;
    }
    const state = actionState(action, row, context);
    if (state.available) able.push(key);
    else refused.push({ key, reason: state.reason ?? UNAVAILABLE });
  }
  return { able, refused };
}

/** The refused, by reason, the commonest first; ties keep their order. */
export interface RefusalGroup {
  reason: string;
  keys: RecordKey[];
}

export function refusalsByReason(
  refused: readonly ActionRefusal[],
): RefusalGroup[] {
  const groups = new Map<string, RecordKey[]>();
  for (const { key, reason } of refused) {
    const keys = groups.get(reason);
    if (keys) keys.push(key);
    else groups.set(reason, [key]);
  }
  return [...groups]
    .map(([reason, keys]) => ({ reason, keys }))
    .sort((left, right) => right.keys.length - left.keys.length);
}

/**
 * The soonest time after `now` any of `rows` changes its availability on
 * its own (`changesAt`), or `null` when none will.
 */
export function nextChange(
  list: RecordActions | undefined,
  rows: readonly RecordRow[],
  now: number,
): number | null {
  let soonest: number | null = null;
  for (const action of list ?? []) {
    if (!action.changesAt) continue;
    for (const row of rows) {
      let at: number | null | undefined;
      try {
        at = action.changesAt(row, { now });
      } catch {
        at = null;
      }
      if (typeof at === 'number' && Number.isFinite(at) && at > now)
        soonest = soonest === null ? at : Math.min(soonest, at);
    }
  }
  return soonest;
}

/**
 * A record the engine did not send the command to, because the action
 * refused it when it came to its turn. It carries the reason as the host
 * gave it (a key stays a key), where a thrown error is read for the
 * source's words.
 */
export class ActionRefusedError extends Error {
  readonly reason: string;
  constructor(reason: string) {
    super(reason);
    this.name = 'ActionRefusedError';
    this.reason = reason;
  }
}

/**
 * Sends one record the command as the engine does: asked again at the
 * moment it goes (the state may have changed since it was picked), and
 * refused with the reason rather than sent where it no longer takes it.
 */
export async function runOne(
  action: RecordAction,
  row: RecordRow | undefined,
  input: ActionInput,
  now: number,
): Promise<unknown> {
  if (!row) throw new ActionRefusedError(UNSEEN);
  const state = actionState(action, row, { now, input });
  if (!state.available)
    throw new ActionRefusedError(state.reason ?? UNAVAILABLE);
  return action.run(row, input);
}
