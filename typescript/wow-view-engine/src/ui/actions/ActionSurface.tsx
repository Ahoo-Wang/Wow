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

import { useCallback, useEffect, useRef, type ReactNode } from 'react';
import type { RecordRow } from '../../record/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import {
  offeredAt,
  type ActionInput,
  type RecordAction,
  type RecordActions,
} from '../../runtime/actions.js';
import {
  useRecordActions,
  type RecordActionSlots,
  type RecordActionTable,
  type RecordActionsController,
} from '../../react/index.js';
import type { SelectionContext } from '../record/SelectionBar.js';
import { useSayWith, useViewMessages } from '../kit/MessagesProvider.js';
import { ActionDialog } from './ActionDialog.js';
import { BulkActionButtons, RowActionButtons } from './ActionButtons.js';
import { BulkStatus, outcomeSentence, runningSentence } from './BulkStatus.js';

export interface ActionSurfaceOptions {
  /** The host's declared actions on this surface's records. */
  actions?: RecordActions;
  /** The host's own markup, drawn after the declared actions. */
  slots?: RecordActionSlots;
  table: RecordActionTable;
  runtime: RecordViewRuntime | null;
  /** What reading the view again means here; the table's own by default. */
  refresh?(): void;
  /** The record the detail holds, when the page does not. */
  also?: readonly RecordRow[];
  /** The surface's voice, which says a command's start and its outcome. */
  say(message: string): void;
  /** Whether the selection may be acted on: a read-only board's panel may not. */
  bulk?: boolean;
}

/** What a record surface draws of its actions, each where it goes. */
export interface ActionSurface {
  controller: RecordActionsController;
  /** A record's actions, in its row or card; `undefined` when it has none. */
  row?(row: RecordRow): ReactNode;
  /** A record's actions in its detail; `undefined` when it has none. */
  detail?(row: RecordRow): ReactNode;
  /** The selection's actions; `undefined` when nothing acts on a selection. */
  bulk?(context: SelectionContext): ReactNode;
  /** The command's line: how far it has come, what it came to. */
  status: ReactNode;
  /** The question and the form, while one is waiting. */
  dialog: ReactNode;
}

/**
 * A record surface's actions (host-integration.md 5): the host's declared
 * ones placed by the engine — a record's in its row, card and detail, the
 * selection's in its bar — then the host's slots, the escape hatch; one
 * runner for every command on the surface, its line above the rows and its
 * start and outcome said aloud; the question and the form in one dialog.
 */
export function useActionSurface({
  actions,
  slots,
  table,
  runtime,
  refresh,
  also,
  say,
  bulk: selectable = true,
}: ActionSurfaceOptions): ActionSurface {
  const messages = useViewMessages();
  const sayWith = useSayWith();
  const controller = useRecordActions({
    actions,
    table,
    ...(refresh ? { refresh } : {}),
    ...(also ? { also } : {}),
  });
  const reread = refresh ?? table.refresh;
  const { start, busy, runCommand, forRow, forSelection } = controller;

  // A command's start and its end are said on the surface's voice: the
  // line above the rows says them too, but a region that appears with its
  // words in it is not read by every screen reader, and the reader's focus
  // is on the row or the dialog, not on the line.
  const { running, outcome } = controller;
  const started = useRef(false);
  useEffect(() => {
    if (running && !started.current)
      say(runningSentence(running, messages, sayWith));
    started.current = running !== null;
  }, [running, say, messages, sayWith]);
  const told = useRef(outcome);
  useEffect(() => {
    if (outcome && outcome !== told.current)
      say(outcomeSentence(outcome, messages, sayWith));
    told.current = outcome;
  }, [outcome, say, messages, sayWith]);

  const onStart = useCallback(
    (place: 'row' | 'detail' | 'bulk', row?: RecordRow) =>
      (action: RecordAction, input?: ActionInput) =>
        start(action.id, place, row, input),
    [start],
  );

  const drawRow = (place: 'row' | 'detail') => {
    const declared = offeredAt(actions, place).length > 0;
    const slot = slots?.row;
    if (!declared && !slot) return undefined;
    return (row: RecordRow) => (
      <>
        {declared && (
          <RowActionButtons
            row={row}
            place={place}
            views={forRow(row, place)}
            busy={busy}
            onStart={onStart(place, row)}
          />
        )}
        {runtime &&
          slot?.({
            row,
            runtime,
            refresh: reread,
            busy,
            run: command =>
              runCommand(
                { keys: [row.key], select() {}, refresh: reread },
                command,
              ),
          })}
      </>
    );
  };

  const declaredBulk = offeredAt(actions, 'bulk').length > 0;
  const bulkSlot = slots?.bulk;
  const drawBulk =
    selectable && (declaredBulk || bulkSlot)
      ? (context: SelectionContext) => (
          <>
            {declaredBulk && (
              <BulkActionButtons
                views={forSelection()}
                busy={busy}
                onStart={onStart('bulk')}
              />
            )}
            {bulkSlot?.({
              ...context,
              refresh: reread,
              busy,
              run: command =>
                runCommand(
                  {
                    keys: context.keys,
                    select: context.select,
                    refresh: reread,
                  },
                  command,
                ),
            })}
          </>
        )
      : undefined;

  return {
    controller,
    ...optional('row', drawRow('row')),
    ...optional('detail', drawRow('detail')),
    ...optional('bulk', drawBulk),
    status: <BulkStatus command={controller} />,
    dialog: (
      <ActionDialog
        pending={controller.pending}
        onInput={controller.setInput}
        onOnlyAble={controller.onlyAble}
        onConfirm={controller.confirm}
        onCancel={controller.cancel}
      />
    ),
  };
}

/** `{ [name]: value }`, or nothing where there is no value. */
function optional<K extends string, V>(
  name: K,
  value: V | undefined,
): { [P in K]?: V } {
  return (value === undefined ? {} : { [name]: value }) as { [P in K]?: V };
}
