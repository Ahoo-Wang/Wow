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

import type { FunctionKind } from "@ahoo-wang/wow-client";
import type {
  RecordKey,
  RecordRow,
  ViewEngine,
} from "@ahoo-wang/wow-view-engine";
import type {
  RecordDetailSection,
  RecordDetailSectionContext,
} from "@ahoo-wang/wow-view-engine/react";
import type {
  EmbeddedViewProps,
  RecordDetailOptions,
} from "@ahoo-wang/wow-view-engine/ui";
import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router";
import type { ApplyRetrySpec, ChangeFunction } from "@/generated";
import { useI18n } from "@/i18n.tsx";
import type { ExecutionCommands } from "../executionCommands.ts";
import { ExecutionHistory } from "./ExecutionHistory.tsx";
import { FunctionForm, RetrySpecForm } from "./ExecutionForms.tsx";
import { StackTrace } from "./StackTrace.tsx";

/** The route parameter naming the execution open in the detail. */
export const ID_PARAM = "id";

/** The execution's state, as the whole record carries it. */
interface ExecutionState {
  retrySpec?: ApplyRetrySpec;
  function?: {
    contextName: string;
    processorName: string;
    name: string;
    functionKind: FunctionKind;
  };
  error?: { stackTrace?: string };
}

function stateOf(row: RecordRow): ExecutionState {
  const state = row.data.state;
  return state && typeof state === "object" ? (state as ExecutionState) : {};
}

export interface ExecutionDetailOptions {
  engine: ViewEngine;
  commands: ExecutionCommands;
  locale: string;
  messages: EmbeddedViewProps["messages"];
}

/**
 * The failed executions' record detail (rebuild proposal, batch 4): which
 * execution is open is the address's `id`, so a link opens it — on the
 * current page or not — and the console's own sections sit among the
 * engine's field groups, each beside what it is about: the function form
 * after the function, the stack trace after the error (read the console's
 * way, so the engine leaves that field out), the retry spec form after the
 * retry state, and the history last.
 */
export function useExecutionDetail({
  engine,
  commands,
  locale,
  messages,
}: ExecutionDetailOptions): RecordDetailOptions {
  const { t } = useI18n();
  const [searchParams, setSearchParams] = useSearchParams();
  const open = searchParams.get(ID_PARAM);

  const onOpenChange = useCallback(
    (key: RecordKey | null) => {
      const next = key === null ? null : String(key);
      if (next === open) return;
      setSearchParams(
        (current) => {
          const params = new URLSearchParams(current);
          if (next === null) params.delete(ID_PARAM);
          else params.set(ID_PARAM, next);
          return params;
        },
        // Opening one execution after another is reading, not navigating.
        { replace: true },
      );
    },
    [open, setSearchParams],
  );

  const sections = useCallback(
    ({
      row,
      complete,
      refresh,
    }: RecordDetailSectionContext): RecordDetailSection[] => {
      const id = String(row.key);
      const state = stateOf(row);
      const target = state.function;
      const spec = state.retrySpec;
      const history: RecordDetailSection = {
        id: "history",
        title: t("Execution history"),
        render: () => (
          <ExecutionHistory
            // Read again once the execution has changed.
            key={String(row.data.eventTime)}
            engine={engine}
            id={id}
            locale={locale}
            messages={messages}
          />
        ),
      };
      // The forms start from the record's values and the trace is the
      // record's, so they wait for the whole record; the page's row may not
      // carry them. A record that cannot be read whole gets the history
      // alone, beside the engine's reason.
      if (!complete) return [history];
      return [
        {
          id: "change-function",
          title: t("Change function"),
          placement: { after: "function" },
          render: () =>
            target && (
              <FunctionForm
                key={JSON.stringify(target)}
                target={target satisfies ChangeFunction}
                change={(next) => commands.changeFunction(id, next)}
                onChanged={refresh}
              />
            ),
        },
        {
          id: "stack-trace",
          title: t("Stack trace"),
          placement: { after: "error" },
          fields: ["state.error.stackTrace"],
          render: () => <StackTrace value={state.error?.stackTrace ?? ""} />,
        },
        {
          id: "retry-spec",
          title: t("Apply retry specification"),
          placement: { after: "retry" },
          render: () =>
            spec && (
              <RetrySpecForm
                key={JSON.stringify(spec)}
                spec={spec}
                apply={(next) => commands.applyRetrySpec(id, next)}
                onApplied={refresh}
              />
            ),
        },
        history,
      ];
    },
    [commands, engine, locale, messages, t],
  );

  return useMemo(
    () => ({ open, onOpenChange, sections }),
    [open, onOpenChange, sections],
  );
}
