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

import type { FilterTree, ViewEngine } from "@ahoo-wang/wow-view-engine";
import {
  EmbeddedView,
  useSurfaceTheme,
  type EmbeddedViewProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { useMemo } from "react";
import { EXECUTION_HISTORY_VIEW } from "@/views/executionHistory.ts";

export interface ExecutionHistoryProps {
  engine: ViewEngine;
  /** The execution whose streams are read: the view's scope. */
  id: string;
  locale: string;
  messages: EmbeddedViewProps["messages"];
}

/**
 * An execution's history in its detail: the event stream's one system view,
 * embedded with the execution as its scope — pages, the newest first, and
 * each stream's events by type. It reads when the detail draws it, which is
 * when the reader opens the execution.
 *
 * The detail is a popup of the workbench's surface, and an embed is a
 * surface of its own; surfaces do not nest, so the embed takes the mode the
 * detail is drawn in (`useSurfaceTheme`) rather than resolving one of its
 * own that could differ.
 */
export function ExecutionHistory({
  engine,
  id,
  locale,
  messages,
}: ExecutionHistoryProps) {
  const theme = useSurfaceTheme();
  // A new scope is a new query: one per execution, not one per render.
  const scope = useMemo<FilterTree>(
    () => ({
      op: "and",
      children: [{ field: "aggregateId", operator: "EQ", value: id }],
    }),
    [id],
  );
  return (
    <EmbeddedView
      engine={engine}
      instanceId={EXECUTION_HISTORY_VIEW}
      scopeFilter={scope}
      interaction="interactive"
      openInWorkbench={false}
      headingLevel={4}
      theme={theme}
      locale={locale}
      messages={messages}
      // A stream opens read whole, over the execution's detail (G20): each
      // event by its type, its payload key by key — the failure's stack
      // among it — as the old detail's 「事件载荷」 read it.
      detail
    />
  );
}
