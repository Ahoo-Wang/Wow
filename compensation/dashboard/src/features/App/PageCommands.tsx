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
import { bind, ViewHost } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n } from "@/i18n.tsx";
import { consoleEngine } from "@/views/engine.ts";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import { EXECUTION_HISTORY_SOURCE } from "@/views/executionHistory.ts";
import { engineMessages } from "@/views/messages.ts";
import { executionsPath } from "@/views/routes.ts";
import { useExecutionDetail } from "../Executions/detail/useExecutionDetail.tsx";
import {
  executionCommands,
  type ExecutionCommands,
} from "../Executions/executionCommands.ts";
import { useExecutionActions } from "../Executions/useExecutionActions.tsx";

interface PageCommandsProps {
  /** A test's own engine and commands; the console's by default. */
  engine?: ViewEngine;
  commands?: ExecutionCommands;
  children: ReactNode;
}

/**
 * The console's commands (host-integration.md 4.2): how a failed execution
 * is read (D60) and the compensation commands on every record view over
 * them, the workbench's and a board's record panel alike (D39), bound to
 * the resource — each page its own, keyed by the page, so a bulk run or a
 * confirmation a page started goes with it.
 */
export function PageCommands(props: PageCommandsProps) {
  return <Bound key={useLocation().pathname} {...props} />;
}

function Bound({
  engine = consoleEngine(),
  commands,
  children,
}: PageCommandsProps) {
  const { locale } = useI18n();
  const [sent] = useState(() => commands ?? executionCommands());
  const { actions, bulk, dialog } = useExecutionActions(sent);
  const reading = useExecutionDetail({
    engine,
    history: engine.resolveSource(EXECUTION_HISTORY_SOURCE),
    commands: sent,
    locale,
    messages: engineMessages(locale),
  });
  const bindings = useMemo(
    () => [
      bind(EXECUTION_FAILED, { route: executionsPath, reading, actions, bulk }),
    ],
    [reading, actions, bulk],
  );
  return (
    <ViewHost bindings={bindings}>
      {children}
      {dialog}
    </ViewHost>
  );
}

/** The pages' layout, in a chunk beside the shell's. */
export default function PageLayout() {
  return (
    <PageCommands>
      <Outlet />
    </PageCommands>
  );
}
