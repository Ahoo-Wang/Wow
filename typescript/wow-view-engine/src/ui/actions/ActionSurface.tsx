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
import type { RecordKey } from '../../model/index.js';
import type { RecordRow } from '../../record/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import { reportError, viewPlace } from '../../runtime/failures.js';
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
import type { ViewMessages } from '../kit/messages.js';
import { focusIn, keyboardFell } from '../kit/focus.js';
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
  /**
   * Says a command's outcome instead of `say`, where the surface reads its
   * query back too: the refresh after a command would otherwise say its
   * count over the outcome before a reader heard it
   * (`useQueryAnnouncement`'s `lead`).
   */
  sayOutcome?(message: string): void;
  /**
   * The host's wording and language, where the surface's provider is below
   * this hook — the record view's parts are above the surface they draw —
   * so what is said aloud is said in the words drawn on the line.
   */
  messages?: ViewMessages;
  locale?: string;
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
  sayOutcome = say,
  messages: wording,
  locale,
  bulk: selectable = true,
}: ActionSurfaceOptions): ActionSurface {
  const messages = useViewMessages(wording, locale);
  const sayWith = useSayWith(wording, locale);
  // A command that failed is the host's to hear of (D40): what was thrown,
  // as thrown, with the action and the record it failed on.
  const onError = useCallback(
    (error: unknown, context: { key: RecordKey; operation: string }) => {
      if (!runtime) return;
      reportError(runtime.environment, {
        kind: 'action',
        error,
        context: {
          operation: context.operation,
          recordKey: context.key,
          ...viewPlace(runtime),
        },
      });
    },
    [runtime],
  );
  const controller = useRecordActions({
    actions,
    table,
    onError,
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
  // The line, where the keyboard lands when a command took away what it
  // was pressed on — a row the refresh filtered out, a selection's bar gone
  // with the selection — rather than falling to the page.
  const line = useRef<HTMLDivElement>(null);
  const landing = useRef<{ rows: readonly RecordRow[] } | null>(null);
  useEffect(() => {
    if (outcome && outcome !== told.current) {
      sayOutcome(outcomeSentence(outcome, messages, sayWith));
      landing.current = { rows: table.rows };
    }
    told.current = outcome;
  }, [outcome, sayOutcome, messages, sayWith, table.rows]);
  // Watched until the refresh after the command has landed and been drawn:
  // the row a press was on goes then, not when the command settles.
  useEffect(() => {
    const armed = landing.current;
    if (!armed) return;
    if (keyboardFell()) focusIn(line.current);
    if (table.rows !== armed.rows) landing.current = null;
  });

  // The control a question goes back to as it closes: the button pressed,
  // or the menu's own button for an item that went with its menu.
  const opener = useRef<HTMLElement | null>(null);
  const onStart = useCallback(
    (place: 'row' | 'detail' | 'bulk', row?: RecordRow) =>
      (
        action: RecordAction,
        input?: ActionInput,
        from?: HTMLElement | null,
      ) => {
        opener.current = from ?? null;
        start(action.id, place, row, input);
      },
    [start],
  );
  const finalFocus = useCallback(() => {
    const from = opener.current;
    return from?.isConnected ? from : true;
  }, []);

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
              runCommand({ keys: [row.key], refresh: reread }, command),
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
    status: <BulkStatus ref={line} command={controller} />,
    dialog: (
      <ActionDialog
        pending={controller.pending}
        onInput={controller.setInput}
        onOnlyAble={controller.onlyAble}
        onConfirm={controller.confirm}
        onCancel={controller.cancel}
        finalFocus={finalFocus}
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
