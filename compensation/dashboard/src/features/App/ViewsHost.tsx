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

import { useMemo, useState, type ReactNode } from "react";
import { Outlet, useLocation } from "react-router";
import type { ViewEngine } from "@ahoo-wang/wow-view-engine";
import { bind, ViewEngineProvider } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { consoleEngine } from "@/views/engine.ts";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import {
  EXECUTION_HISTORY,
  EXECUTION_HISTORY_SOURCE,
} from "@/views/executionHistory.ts";
import { engineMessages } from "@/views/messages.ts";
import {
  boardPath,
  EVENTS_PATH,
  EXECUTIONS_PATH,
  useViewNavigation,
  withView,
} from "@/views/navigation.ts";
import { OVERVIEW } from "@/views/overview.ts";
import { useExecutionDetail } from "../Executions/detail/useExecutionDetail.tsx";
import {
  executionCommands,
  type ExecutionCommands,
} from "../Executions/executionCommands.ts";
import { useExecutionActions } from "../Executions/useExecutionActions.tsx";

export interface ViewsHostProps {
  /** The engine; the console's one engine by default, a test's own otherwise. */
  engine?: ViewEngine;
  /** What the execution commands send; the service by default. */
  commands?: ExecutionCommands;
  children: ReactNode;
}

/** Where each resource's views live in the console's address. */
const ROUTES = {
  [EXECUTION_FAILED]: (view: string | null) => withView(EXECUTIONS_PATH, view),
  [EXECUTION_HISTORY]: (view: string | null) => withView(EVENTS_PATH, view),
  [OVERVIEW]: boardPath,
};

const ROUTED = Object.entries(ROUTES).map(([definitionId, route]) =>
  bind(definitionId, { route }),
);

/**
 * The console as the view engine's host (host-integration.md 4): its one
 * engine, said in the language in force, where each resource's views live
 * in the address, and — per page (`PageCommands`) — how a failed execution
 * is read (D60) and the compensation commands on every record view over
 * the failed executions, the workbench's and a board's record panel alike
 * (D39). The pages take only what differs where they stand.
 */
export function ViewsHost({
  engine = consoleEngine(),
  commands,
  children,
}: ViewsHostProps) {
  const { locale } = useI18n();
  const navigate = useViewNavigation();
  const { pathname } = useLocation();
  const [issued] = useState(() => commands ?? executionCommands());
  return (
    <ViewEngineProvider
      engine={engine}
      locale={locale}
      messages={engineMessages(locale)}
      navigate={navigate}
      bindings={ROUTED}
    >
      {/* Each page its own: a bulk run or a confirmation a page started
          goes with that page — a run carries on at the service, but its
          line and its last refresh are that page's — and never shows on
          the next one (keyed by the page). */}
      <PageCommands key={pathname} engine={engine} commands={issued}>
        {children}
      </PageCommands>
    </ViewEngineProvider>
  );
}

/** The failed executions' reading and commands, for one page. */
function PageCommands({
  engine,
  commands,
  children,
}: {
  engine: ViewEngine;
  commands: ExecutionCommands;
  children: ReactNode;
}) {
  const { locale } = useI18n();
  const { actions, bulk, dialog } = useExecutionActions(commands);
  const reading = useExecutionDetail({
    engine,
    history: engine.resolveSource(EXECUTION_HISTORY_SOURCE),
    commands,
    locale,
    messages: engineMessages(locale),
  });
  const bindings = useMemo(
    () => [
      bind(EXECUTION_FAILED, {
        route: ROUTES[EXECUTION_FAILED],
        reading,
        actions,
        bulk,
      }),
    ],
    [reading, actions, bulk],
  );
  return (
    <ViewEngineProvider bindings={bindings}>
      {children}
      {dialog}
    </ViewEngineProvider>
  );
}

/** The route every page of views sits under: one host for all of them. */
export default function ViewsLayout() {
  return (
    <ViewsHost>
      <Outlet />
    </ViewsHost>
  );
}
