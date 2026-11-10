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

import { useCallback, useMemo, useState } from 'react';
import type { FieldOption, RecordKey } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import { rowNameOf } from '../record/path.js';
import {
  ActionInputError,
  actionState,
  asksFirst,
  choiceOf,
  confirmOf,
  initialInput,
  invalidInput,
  missingInput,
  offeredAt,
  refusalsByReason,
  runOne,
  splitFor,
  type ActionConfirm,
  type ActionInput,
  type ActionPlace,
  type RecordAction,
  type RecordActions,
  type RefusalGroup,
} from '../runtime/actions.js';
import {
  useActionRunner,
  type ActionFailureContext,
  type ActionRunner,
  type BulkRun,
  type BulkSelection,
} from './actionRunner.js';
import type { RuntimeEnvironment } from '../runtime/environment.js';
import { SYSTEM_CLOCK, useAskedAt, useRulesClock } from './rulesClock.js';

/** What of a record table the actions read and act on. */
export interface RecordActionTable {
  rows: readonly RecordRow[];
  selection: readonly RecordKey[];
  selectedRows: readonly RecordRow[];
  select(keys: readonly RecordKey[]): void;
  refresh(): void;
  /**
   * The field a reader names a row by (`RecordCapability.rowName`), which
   * the dialog and the outcome line say a record as; its key where absent.
   */
  rowName?: string;
}

export interface RecordActionsOptions {
  /** The declared actions (`actions()`); none leaves only the runner. */
  actions?: RecordActions;
  table: RecordActionTable;
  /**
   * What reading the view again after a command means here: the table's
   * own by default; a board's record panel reads the whole board again.
   */
  refresh?(): void;
  /**
   * Records shown beyond the table's page — the record detail's, opened by
   * a link — whose actions are drawn and whose availability can change on
   * its own as well.
   */
  also?: readonly RecordRow[];
  /** How many records are in flight at once; a handful by default. */
  concurrency?: number;
  /**
   * Whose clock (`now`) the rules are asked at: the engine's
   * (`runtime.environment`), so a pinned environment pins the actions too.
   * The current time when left out. A different object is a different
   * clock, read at once.
   */
  environment?: Pick<RuntimeEnvironment, 'now'>;
  /**
   * Told of what a command threw on a record, as it was thrown — not of an
   * action's own refusal, nor of an abort (`ActionRunnerOptions.onError`).
   */
  onError?(error: unknown, context: ActionFailureContext): void;
}

/** One action as a record's row, menu or detail draws it. */
export interface RowActionView {
  action: RecordAction;
  available: boolean;
  /** Why not, as the host wrote it (a key stays a key); `null` when able. */
  reason: string | null;
  /**
   * A choice's options, each with whether this record takes it — the value
   * it already holds is refused by the host's rule and not offered again.
   * `null` for an action that is not a choice.
   */
  choices: readonly { option: FieldOption; available: boolean }[] | null;
}

/** One action as the selection's bar draws it. */
export interface BulkActionView {
  action: RecordAction;
  /** How many records are picked: what the primary action's button counts. */
  count: number;
  /**
   * How many of them take it now, before any input — 「催发货 2/4 条」; the
   * dialog says which do not and why.
   */
  able: number;
  /**
   * Why none of them takes it, the commonest reason as the host wrote it;
   * `null` while one does.
   */
  reason: string | null;
  /** A choice's options, or `null`. */
  choices: readonly FieldOption[] | null;
}

/** An action pressed and waiting on the reader: its question, its form. */
export interface PendingAction {
  action: RecordAction;
  place: ActionPlace;
  /** What it runs over: the record, or the selection. */
  keys: readonly RecordKey[];
  input: ActionInput;
  /** The declared question for this input, or `null` for the engine's own. */
  confirm: ActionConfirm | null;
  /** Whether it has a form to fill (a choice is filled by its option). */
  form: boolean;
  /** The records that take it now. */
  able: readonly RecordKey[];
  /** The ones that will not be sent, by reason, the commonest first. */
  refused: readonly RefusalGroup[];
  /** Required fields still blank, by name. */
  missing: readonly string[];
  /**
   * Fields whose value their `check` refuses, by name, with why — and,
   * on a form that came back, those the source refused while they keep the
   * value it was sent.
   */
  invalid: Readonly<Record<string, string>>;
  /** The option a choice picked, as the words `{value}` is said with. */
  value: string | null;
  /**
   * Set on a form that came back because the source refused what was typed
   * (`ActionInputError`): its `reason` where it named none of the form's
   * fields, said above them, else `null`, the reasons standing under the
   * fields it named (`invalid`).
   */
  returned?: { reason: string | null };
  /**
   * What each record is called, where the definition names rows
   * (`RecordCapability.rowName`); the key where absent.
   */
  names?: ReadonlyMap<RecordKey, string>;
}

/**
 * The declared actions of one record surface (host-integration.md 5): what
 * each record and the selection are offered, the press that asks first or
 * runs, the question or form waiting on the reader, and the one runner every
 * command on the surface goes through — a few at a time, its progress, its
 * stop, its outcome, the refresh after.
 *
 * Availability is asked at the engine's clock, which moves on by itself when
 * a record's rule says it will change (`changesAt`): the host keeps no timer.
 */
export interface RecordActionsController extends Pick<
  ActionRunner,
  'running' | 'outcome' | 'stop' | 'dismiss'
> {
  /**
   * The moment the rules are asked at, moved on when a record's rule says it
   * changes.
   */
  now: Date;
  /** A command is running: nothing else starts until it settles. */
  busy: boolean;
  /** The actions offered on a record at `row` or `detail`, hidden ones left out. */
  forRow(row: RecordRow, place: 'row' | 'detail'): readonly RowActionView[];
  /** The actions offered on the selection, left out where hidden on all of it. */
  forSelection(): readonly BulkActionView[];
  /**
   * Presses an action: on `row`, at `row` or `detail`; on the selection at
   * `bulk`. With a question or a form it waits (`pending`), otherwise it
   * runs. A choice's option comes as the input.
   */
  start(
    id: string,
    place: ActionPlace,
    row?: RecordRow,
    input?: ActionInput,
  ): void;
  pending: PendingAction | null;
  setInput(name: string, value: unknown): void;
  /** Picks only the records that take it — 「只选能做的」. */
  onlyAble(): void;
  /** Runs what is pending; the refused are reported, not sent. */
  confirm(): void;
  cancel(): void;
  /** Runs a host's own command (a slot's) on this surface's runner. */
  runCommand(selection: BulkSelection, command: BulkRun): void;
}

interface Pressed {
  action: RecordAction;
  place: ActionPlace;
  keys: readonly RecordKey[];
  input: ActionInput;
  /** The records as they were pressed, where the table no longer has them. */
  rows: readonly RecordRow[];
  /** What the source refused of the input this form was sent with. */
  returned?: Returned;
}

/** A form handed back: the source's refusal of the input it was sent. */
interface Returned {
  reason: string;
  /** The form's fields it named, with why. */
  fields: Readonly<Record<string, string>>;
  /** The input it refused, which a field's reason holds for. */
  input: ActionInput;
}

const NO_ROWS: readonly RecordRow[] = [];

/**
 * The form a run hands back: the records whose input the source refused,
 * in the order they were pressed, and why — the first refusal's reason, and
 * each field of the form any of them named. Nothing where none was, or
 * where the action draws no form to come back to (a choice).
 */
function handBack(
  pressed: Pressed,
  refusals: ReadonlyMap<RecordKey, ActionInputError>,
): Pressed | null {
  const { action, keys, rows, input } = pressed;
  const [first] = refusals.values();
  if (!first || !action.form || choiceOf(action)) return null;
  const fields: Record<string, string> = {};
  for (const refusal of refusals.values())
    for (const [name, reason] of Object.entries(refusal.fields))
      if (name in action.form && !(name in fields)) fields[name] = reason;
  return {
    ...pressed,
    keys: keys.filter(key => refusals.has(key)),
    rows: rows.filter(row => refusals.has(row.key)),
    returned: { reason: first.reason, fields, input },
  };
}

/** The fields the source refused that still hold the value it was sent. */
function stillRefused(
  returned: Returned | undefined,
  input: ActionInput,
): Record<string, string> {
  const refused: Record<string, string> = {};
  if (!returned) return refused;
  for (const [name, reason] of Object.entries(returned.fields))
    if (Object.is(input[name], returned.input[name])) refused[name] = reason;
  return refused;
}

/** The words a choice's `{value}` is said with: the option picked. */
function valueOf(action: RecordAction, input: ActionInput): string | null {
  const choice = choiceOf(action);
  if (!choice) return null;
  const picked = input[choice.name];
  const option = choice.options.find(each => each.value === picked);
  if (option) return option.label;
  // A value outside the options is shown as it is, where it is a value.
  return typeof picked === 'string' || typeof picked === 'number'
    ? String(picked)
    : null;
}

/**
 * What a record and the selection are offered at the moment the rules are
 * asked at: each record's actions with whether it takes them and why not,
 * a choice's options each asked with its value; the selection's, counted
 * by how many take them.
 */
function useActionViews(
  actions: RecordActions | undefined,
  now: Date,
  selection: readonly RecordKey[],
  selectedRows: readonly RecordRow[],
  rowOf: (key: RecordKey) => RecordRow | undefined,
): Pick<RecordActionsController, 'forRow' | 'forSelection'> {
  const forRow = useCallback(
    (row: RecordRow, place: 'row' | 'detail'): RowActionView[] =>
      offeredAt(actions, place).flatMap(action => {
        const state = actionState(action, row, { now });
        if (state.hidden) return [];
        const choice = choiceOf(action);
        return [
          {
            action,
            available: state.available,
            reason: state.reason,
            choices: choice
              ? choice.options.map(option => ({
                  option,
                  available:
                    state.available &&
                    actionState(action, row, {
                      now,
                      input: { [choice.name]: option.value },
                    }).available,
                }))
              : null,
          },
        ];
      }),
    [actions, now],
  );

  const forSelection = useCallback(
    (): BulkActionView[] =>
      offeredAt(actions, 'bulk')
        .filter(
          action =>
            // Keys whose record is not in hand are shown the action, and
            // refused in the dialog with why.
            selectedRows.length < selection.length ||
            selectedRows.some(row => !actionState(action, row, { now }).hidden),
        )
        .map(action => {
          const split = splitFor(action, selection, rowOf, { now });
          return {
            action,
            count: selection.length,
            able: split.able.length,
            reason:
              split.able.length === 0
                ? (refusalsByReason(split.refused)[0]?.reason ?? null)
                : null,
            choices: choiceOf(action)?.options ?? null,
          };
        }),
    [actions, selectedRows, selection, rowOf, now],
  );
  return { forRow, forSelection };
}

export function useRecordActions({
  actions,
  table,
  refresh,
  also = NO_ROWS,
  concurrency,
  onError,
  environment: clock = SYSTEM_CLOCK,
}: RecordActionsOptions): RecordActionsController {
  const runner = useActionRunner({
    ...(concurrency === undefined ? {} : { concurrency }),
    ...(onError ? { onError } : {}),
  });
  const [now, setNow] = useAskedAt(clock);
  const [pressed, setPressed] = useState<Pressed | null>(null);
  const reread = refresh ?? table.refresh;
  const { rows, selection, selectedRows, select, rowName } = table;

  useRulesClock(actions, rows, also, now, clock, setNow);

  const known = useMemo(() => {
    const map = new Map<RecordKey, RecordRow>();
    for (const row of [...(pressed?.rows ?? []), ...also, ...rows])
      map.set(row.key, row);
    return map;
  }, [pressed, also, rows]);
  const rowOf = useCallback((key: RecordKey) => known.get(key), [known]);
  // What the dialog and the outcome line call the records a command is for.
  const namesOf = useCallback(
    (keys: readonly RecordKey[]) =>
      rowName === undefined
        ? undefined
        : new Map(
            keys.map(key => [
              key,
              rowNameOf(rowOf(key) ?? { key, data: {} }, rowName),
            ]),
          ),
    [rowName, rowOf],
  );

  const busy = runner.running !== null;
  const { run } = runner;

  const execute = useCallback(
    (pressed: Pressed) => {
      const { action, place, keys, input } = pressed;
      const confirm = confirmOf(action, input);
      const value = valueOf(action, input);
      const names = namesOf(keys);
      // The records whose input the source refused, which the form comes
      // back over once the run has settled — the runner's refresh.
      const refusals = new Map<RecordKey, ActionInputError>();
      run(
        {
          keys,
          // A record's own command leaves the selection as it found it.
          ...(place === 'bulk' ? { select } : {}),
          refresh: () => {
            reread();
            const back = handBack(pressed, refusals);
            if (back) setPressed(back);
          },
        },
        {
          title: confirm?.action ?? action.label,
          ...(value === null ? {} : { values: { value } }),
          operation: action.id,
          ...(action.timeout === undefined ? {} : { timeout: action.timeout }),
          ...(action.done === undefined ? {} : { done: action.done }),
          ...(names ? { names } : {}),
          each: async (key, options) => {
            try {
              return await runOne(
                action,
                rowOf(key),
                input,
                clock.now(),
                options,
              );
            } catch (error) {
              if (error instanceof ActionInputError) refusals.set(key, error);
              throw error;
            }
          },
        },
      );
    },
    [run, select, reread, rowOf, namesOf, clock],
  );

  const start = useCallback(
    (id: string, place: ActionPlace, row?: RecordRow, given?: ActionInput) => {
      if (busy) return;
      const action = actions?.find(each => each.id === id);
      if (!action) return;
      if (place !== 'bulk' && !row) return;
      const input = { ...initialInput(action), ...given };
      const target: Pressed = {
        action,
        place,
        keys: place === 'bulk' ? [...selection] : [row!.key],
        input,
        rows: place === 'bulk' ? selectedRows : [row!],
      };
      if (target.keys.length === 0) return;
      setNow(clock.now());
      if (asksFirst(action, place, input)) setPressed(target);
      else execute(target);
    },
    [actions, busy, selection, selectedRows, execute, clock, setNow],
  );

  const pending = useMemo<PendingAction | null>(() => {
    if (!pressed) return null;
    const { action, place, keys, input, returned } = pressed;
    const split = splitFor(action, keys, rowOf, { now, input });
    const choice = choiceOf(action);
    const names = namesOf(keys);
    return {
      action,
      place,
      keys,
      input,
      confirm: confirmOf(action, input),
      form: action.form !== undefined && choice === null,
      able: split.able,
      refused: refusalsByReason(split.refused),
      missing: missingInput(action, input),
      invalid: {
        ...stillRefused(returned, input),
        ...invalidInput(action, input),
      },
      value: valueOf(action, input),
      ...(names ? { names } : {}),
      ...(returned
        ? {
            returned: {
              reason:
                Object.keys(returned.fields).length > 0
                  ? null
                  : returned.reason,
            },
          }
        : {}),
    };
  }, [pressed, rowOf, namesOf, now]);

  const { forRow, forSelection } = useActionViews(
    actions,
    now,
    selection,
    selectedRows,
    rowOf,
  );

  const setInput = useCallback(
    (name: string, value: unknown) =>
      setPressed(last =>
        last ? { ...last, input: { ...last.input, [name]: value } } : last,
      ),
    [],
  );

  const onlyAble = useCallback(() => {
    if (!pending || !pressed) return;
    const able = [...pending.able];
    if (pressed.place === 'bulk') select(able);
    setPressed({ ...pressed, keys: able });
  }, [pending, pressed, select]);

  const confirm = useCallback(() => {
    if (!pressed || !pending) return;
    if (
      pending.missing.length > 0 ||
      Object.keys(pending.invalid).length > 0 ||
      pending.able.length === 0
    )
      return;
    setPressed(null);
    execute(pressed);
  }, [pressed, pending, execute]);

  const cancel = useCallback(() => setPressed(null), []);

  return {
    now,
    busy,
    forRow,
    forSelection,
    start,
    pending,
    setInput,
    onlyAble,
    confirm,
    cancel,
    runCommand: run,
    running: runner.running,
    outcome: runner.outcome,
    stop: runner.stop,
    dismiss: runner.dismiss,
  };
}
