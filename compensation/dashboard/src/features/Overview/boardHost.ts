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

import { useCallback } from "react";
import type { ViewSource, ViewStore } from "@ahoo-wang/wow-view-engine";
import type {
  BulkCommand,
  RecordActionSlots,
} from "@ahoo-wang/wow-view-engine/react";
import type { EmbeddedDashboardProps } from "@ahoo-wang/wow-view-engine/ui";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import type { ExecutionCommands } from "../Executions/executionCommands.ts";

export { engineMessages } from "@/views/messages.ts";

/** What a board's page takes, for tests; the service and this browser otherwise. */
export interface BoardPageProps {
  /** The store the engines read and write. */
  store?: ViewStore;
  /** Where the failed executions come from instead of the service. */
  source?: ViewSource;
  /** Where the event streams come from instead. */
  historySource?: ViewSource;
  /** What the row and bulk commands send instead. */
  commands?: ExecutionCommands;
}

/** What a board asks of its host for each record panel. */
type RecordPanels = NonNullable<EmbeddedDashboardProps["recordPanel"]>;

/**
 * The failed executions' commands on every record panel over them (D39): a
 * row's and a selection's, as on their workbench, run by the one bulk
 * command whose line the panel shows. Other panels take nothing.
 */
export function useRecordPanels(
  actions: RecordActionSlots,
  bulk: BulkCommand,
): RecordPanels {
  return useCallback<RecordPanels>(
    (panel) =>
      panel.runtime?.definition.id === EXECUTION_FAILED
        ? { actions, bulk }
        : undefined,
    [actions, bulk],
  );
}
