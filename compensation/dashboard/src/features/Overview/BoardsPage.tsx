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

import { useCallback, useState } from "react";
import { useSearchParams } from "react-router";
import { useViewEngine } from "@ahoo-wang/wow-view-engine/react";
import { DashboardWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n, type Locale } from "@/i18n.tsx";
import { executionEngineOptions, localViewStore } from "@/views/engine.ts";
import {
  VIEW_PARAM,
  useBoardFilters,
  useViewNavigation,
} from "@/views/navigation.ts";
import { OVERVIEW } from "@/views/overview.ts";
import { executionCommands } from "../Executions/executionCommands.ts";
import { useExecutionActions } from "../Executions/useExecutionActions.tsx";
import {
  engineMessages,
  useRecordPanels,
  type BoardPageProps,
} from "./boardHost.ts";

interface LocalizedBoardsProps extends BoardPageProps {
  locale: Locale;
}

function LocalizedBoards({
  locale,
  store,
  source,
  historySource,
  commands,
}: LocalizedBoardsProps) {
  const engine = useViewEngine(
    executionEngineOptions({
      locale,
      store: store ?? localViewStore(),
      source,
      historySource,
    }),
  );
  const [sent] = useState(() => commands ?? executionCommands());
  const { actions, bulk, dialog } = useExecutionActions(sent);
  const recordPanel = useRecordPanels(actions, bulk);
  const [searchParams, setSearchParams] = useSearchParams();
  const instanceId = searchParams.get(VIEW_PARAM);
  const { initialFilters, onFiltersChange } = useBoardFilters();
  const onNavigate = useViewNavigation();

  const onInstanceChange = useCallback(
    (id: string | null) => {
      if (id === instanceId) return;
      setSearchParams((current) => {
        const next = new URLSearchParams(current);
        if (id === null) next.delete(VIEW_PARAM);
        else next.set(VIEW_PARAM, id);
        return next;
      });
    },
    [instanceId, setSearchParams],
  );

  return (
    <>
      <DashboardWorkbench
        engine={engine}
        definitionId={OVERVIEW}
        instanceId={instanceId}
        onInstanceChange={onInstanceChange}
        initialFilters={initialFilters}
        onFiltersChange={onFiltersChange}
        onNavigate={onNavigate}
        recordPanel={recordPanel}
        // The console's shell already has the page's `main`.
        landmark="region"
        locale={locale}
        messages={engineMessages(locale)}
      />
      {dialog}
    </>
  );
}

/**
 * The dashboard workbench: the overview board and the reader's own boards
 * beside it, built and saved here — in this browser until the Wow storage
 * backend (stage 6). The home page's 「在工作台中打开」 opens the overview
 * here; the board to open is the `view` parameter.
 */
export default function BoardsPage(props: BoardPageProps) {
  const { locale } = useI18n();
  return (
    <div className="boards-page">
      <LocalizedBoards key={locale} locale={locale} {...props} />
    </div>
  );
}
