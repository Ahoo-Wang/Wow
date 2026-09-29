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

import type { ViewEngine, ViewSource } from "@ahoo-wang/wow-view-engine";
import type { RecordDetailSectionContext } from "@ahoo-wang/wow-view-engine/react";
import type {
  EmbeddedViewProps,
  RecordDetailOptions,
} from "@ahoo-wang/wow-view-engine/ui";
import { useCallback, useMemo } from "react";
import type { ExecutionCommands } from "../executionCommands.ts";
import { ExecutionReading } from "./ExecutionReading.tsx";
import { executionTitle } from "./executionState.ts";

export interface ExecutionDetailOptions {
  engine: ViewEngine;
  /** Where the execution's event streams are read. */
  history: ViewSource;
  commands: ExecutionCommands;
  locale: string;
  messages: EmbeddedViewProps["messages"];
}

/**
 * The failed executions' record detail: the execution read the console's
 * way (`ExecutionReading`, D60) under the handler's name, in the engine's
 * panel. Which execution is open is the address's `?id=`, which the
 * engine keeps (`ViewHost`'s router), so a link opens it.
 */
export function useExecutionDetail({
  engine,
  history,
  commands,
  locale,
  messages,
}: ExecutionDetailOptions): RecordDetailOptions {
  const render = useCallback(
    ({ row, complete, refresh }: RecordDetailSectionContext) => (
      <ExecutionReading
        row={row}
        complete={complete}
        refresh={refresh}
        engine={engine}
        history={history}
        commands={commands}
        locale={locale}
        messages={messages}
      />
    ),
    [commands, engine, history, locale, messages],
  );

  return useMemo(() => ({ render, title: executionTitle }), [render]);
}
