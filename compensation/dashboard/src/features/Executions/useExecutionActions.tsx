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

import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import {
  useBulkCommand,
  type BulkCommand,
  type BulkSelection,
  type RecordActionSlots,
} from "@ahoo-wang/wow-view-engine/react";
import { useMemo, useState, type ReactNode } from "react";
import { useI18n } from "@/i18n.tsx";
import {
  BulkCommands,
  ConfirmCommand,
  RowCommands,
  type PendingCommand,
} from "./ExecutionCommandControls.tsx";
import type { ExecutionCommands } from "./executionCommands.ts";
import {
  bulkRun,
  refusedReasons,
  type ExecutionCommand,
} from "./operability.ts";

export interface ExecutionActions {
  /** The workbench's `record.actions`. */
  actions: RecordActionSlots;
  /** The workbench's `record.bulk`, whose line reports every run. */
  bulk: BulkCommand;
  /** The confirmation dialog, rendered once beside the workbench. */
  dialog: ReactNode;
}

/**
 * The compensation commands on the failed-executions workbench (rebuild
 * proposal, batch 3): a row's commands in its row (`actions.row`), a
 * selection's in the toolbar (`actions.bulk`), both run by one
 * `useBulkCommand` — so a row's command and a selection's report on the one
 * line above the rows, the refused rows stay selected, and the page is
 * refreshed afterwards. Which commands an execution takes follows the old
 * queues' rule (`getCompensationCapabilities`).
 */
export function useExecutionActions(
  commands: ExecutionCommands,
): ExecutionActions {
  const { t } = useI18n();
  const bulk = useBulkCommand();
  const [pending, setPending] = useState<PendingCommand | null>(null);
  const running = bulk.running !== null;
  const { run } = bulk;

  const actions = useMemo<RecordActionSlots>(() => {
    const confirm = (
      command: ExecutionCommand,
      selection: BulkSelection,
      rows: readonly RecordRow[],
    ) =>
      setPending({
        command,
        selection,
        rows,
        refused: refusedReasons(command, rows, selection.keys, Date.now()),
      });
    return {
      row: ({ row, refresh }) => {
        // A row's command leaves the selection as it found it.
        const selection: BulkSelection = {
          keys: [row.key],
          refresh,
          select() {},
        };
        return (
          <RowCommands
            row={row}
            running={running}
            onPrepare={() =>
              run(selection, bulkRun({ kind: "prepare" }, [row], commands, t))
            }
            onConfirm={(command) => confirm(command, selection, [row])}
          />
        );
      },
      bulk: (context) => (
        <BulkCommands
          context={context}
          running={running}
          onConfirm={(command, { keys, select, refresh, rows }) =>
            confirm(command, { keys, select, refresh }, rows)
          }
        />
      ),
    };
  }, [commands, run, running, t]);

  const dialog = (
    <ConfirmCommand
      pending={pending}
      onCancel={() => setPending(null)}
      onConfirm={({ command, selection, rows }) => {
        setPending(null);
        run(selection, bulkRun(command, rows, commands, t));
      }}
    />
  );
  return { actions, bulk, dialog };
}
