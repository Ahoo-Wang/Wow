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

import type {
  RecordKey,
  ViewEngine,
  ViewSource,
} from "@ahoo-wang/wow-view-engine";
import type { RecordDetailSectionContext } from "@ahoo-wang/wow-view-engine/react";
import type {
  EmbeddedViewProps,
  RecordDetailOptions,
} from "@ahoo-wang/wow-view-engine/ui";
import { useCallback, useLayoutEffect, useMemo, useRef } from "react";
import { useSearchParams } from "react-router";
import type { ExecutionCommands } from "../executionCommands.ts";
import { ExecutionReading } from "./ExecutionReading.tsx";
import { executionTitle } from "./executionState.ts";

/** The route parameter naming the execution open in the detail. */
export const ID_PARAM = "id";

export interface ExecutionDetailOptions {
  engine: ViewEngine;
  /** Where the execution's event streams are read. */
  history: ViewSource;
  commands: ExecutionCommands;
  locale: string;
  messages: EmbeddedViewProps["messages"];
}

/**
 * The failed executions' record detail: which execution is open is the
 * address's `id`, so a link opens it — on the current page or not — and the
 * execution is read the console's way (`ExecutionReading`, D60) under the
 * handler's name, in the engine's panel.
 */
export function useExecutionDetail({
  engine,
  history,
  commands,
  locale,
  messages,
}: ExecutionDetailOptions): RecordDetailOptions {
  const [searchParams, setSearchParams] = useSearchParams();
  const open = searchParams.get(ID_PARAM);

  // Held by a ref, so the reading changes only with the record open: the
  // rest of the address moving (another view, a link's narrowing) leaves
  // it — and every surface bound to it — as it was.
  const latest = useRef({ open, setSearchParams });
  useLayoutEffect(() => {
    latest.current = { open, setSearchParams };
  });
  const onOpenChange = useCallback((key: RecordKey | null) => {
    const next = key === null ? null : String(key);
    if (next === latest.current.open) return;
    latest.current.setSearchParams(
      (current) => {
        const params = new URLSearchParams(current);
        if (next === null) params.delete(ID_PARAM);
        else params.set(ID_PARAM, next);
        return params;
      },
      // Opening one execution after another is reading, not navigating.
      { replace: true },
    );
  }, []);

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

  return useMemo(
    () => ({ open, onOpenChange, render, title: executionTitle }),
    [open, onOpenChange, render],
  );
}
