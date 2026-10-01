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

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FieldOption, RecordKey } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import {
  actionState,
  asksFirst,
  choiceOf,
  confirmOf,
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

/** What of a record table the actions read and act on. */
export interface RecordActionTable {
  rows: readonly RecordRow[];
  selection: readonly RecordKey[];
  selectedRows: readonly RecordRow[];
  select(keys: readonly RecordKey[]): void;
  refresh(): void;
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
  /** The option a choice picked, as the words `{value}` is said with. */
  value: string | null;
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
  /** The time the rules are asked at now. */
  now: number;
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
}

const NO_ROWS: readonly RecordRow[] = [];

/**
 * The shortest wait before a rule is asked again on its own. A `changesAt`
 * that answers a moment ahead of every clock it is asked at — a target that
 * moves with `now` — would otherwise wake the surface without pause; a rule
 * that really flips sooner is seen at most this late.
 */
const RECHECK_FLOOR = 1_000;

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

export function useRecordActions({
  actions,
  table,
  refresh,
  also = NO_ROWS,
  concurrency,
  onError,
}: RecordActionsOptions): RecordActionsController {
  const runner = useActionRunner({
    ...(concurrency === undefined ? {} : { concurrency }),
    ...(onError ? { onError } : {}),
  });
  const [now, setNow] = useState(Date.now);
  const [pressed, setPressed] = useState<Pressed | null>(null);
  const reread = refresh ?? table.refresh;
  const { rows, selection, selectedRows, select } = table;

  // The next moment any record on screen changes on its own, and a timer
  // that moves the clock there. A clock left behind while rows came in is
  // only ever early: a rule asked too early refuses, and says when it will
  // change, which is at once.
  const next = useMemo(
    () => nextChange(actions, [...rows, ...also], now),
    [actions, rows, also, now],
  );
  useEffect(() => {
    if (next === null) return;
    const timer = setTimeout(
      () => setNow(Date.now()),
      // The longest wait a timer holds; a later change is asked again then.
      Math.min(Math.max(RECHECK_FLOOR, next - Date.now()), 2_147_483_647),
    );
    return () => clearTimeout(timer);
  }, [next]);

  const known = useMemo(() => {
    const map = new Map<RecordKey, RecordRow>();
    for (const row of [...(pressed?.rows ?? []), ...also, ...rows])
      map.set(row.key, row);
    return map;
  }, [pressed, also, rows]);
  const rowOf = useCallback((key: RecordKey) => known.get(key), [known]);

  const busy = runner.running !== null;
  const { run } = runner;

  const execute = useCallback(
    ({ action, place, keys, input }: Pressed) => {
      const confirm = confirmOf(action, input);
      const value = valueOf(action, input);
      run(
        {
          keys,
          // A record's own command leaves the selection as it found it.
          ...(place === 'bulk' ? { select } : {}),
          refresh: reread,
        },
        {
          title: confirm?.action ?? action.label,
          ...(value === null ? {} : { values: { value } }),
          operation: action.id,
          ...(action.timeout === undefined ? {} : { timeout: action.timeout }),
          each: key => runOne(action, rowOf(key), input, Date.now()),
        },
      );
    },
    [run, select, reread, rowOf],
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
      setNow(Date.now());
      if (asksFirst(action, place, input)) setPressed(target);
      else execute(target);
    },
    [actions, busy, selection, selectedRows, execute],
  );

  const pending = useMemo<PendingAction | null>(() => {
    if (!pressed) return null;
    const { action, place, keys, input } = pressed;
    const split = splitFor(action, keys, rowOf, { now, input });
    const choice = choiceOf(action);
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
      value: valueOf(action, input),
    };
  }, [pressed, rowOf, now]);

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
    if (pending.missing.length > 0 || pending.able.length === 0) return;
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
