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

import { useState } from "react";
import { Link } from "react-router";
import { LayoutDashboard } from "lucide-react";
import { useViewEngine } from "@ahoo-wang/wow-view-engine/react";
import { EmbeddedDashboard } from "@ahoo-wang/wow-view-engine/ui";
import { buttonVariants } from "@/components/ui/button";
import { useI18n, type Locale } from "@/i18n.tsx";
import { executionEngineOptions, localViewStore } from "@/views/engine.ts";
import {
  BOARDS_PATH,
  VIEW_PARAM,
  useBoardFilters,
  useViewNavigation,
} from "@/views/navigation.ts";
import { OVERVIEW_BOARD } from "@/views/overview.ts";
import { executionCommands } from "../Executions/executionCommands.ts";
import { useExecutionActions } from "../Executions/useExecutionActions.tsx";
import {
  engineMessages,
  useRecordPanels,
  type BoardPageProps,
} from "./boardHost.ts";

interface LocalizedOverviewProps extends BoardPageProps {
  locale: Locale;
}

/**
 * The board in one language: the definitions carry one language (G12), so a
 * change of language builds a new engine, as the failed executions' page
 * does.
 */
function LocalizedOverview({
  locale,
  store,
  source,
  historySource,
  commands,
}: LocalizedOverviewProps) {
  const { t } = useI18n();
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
  const { initialFilters, onFiltersChange } = useBoardFilters();
  const onNavigate = useViewNavigation();
  const workbench = `${BOARDS_PATH}?${new URLSearchParams({
    [VIEW_PARAM]: OVERVIEW_BOARD,
  })}`;

  return (
    <div className="overview-page">
      <div className="overview-page-actions">
        <Link
          to={workbench}
          state={{ filters: initialFilters ?? undefined }}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          <LayoutDashboard />
          {t("Open in the dashboard workbench")}
        </Link>
      </div>
      <EmbeddedDashboard
        className="overview-board"
        engine={engine}
        instanceId={OVERVIEW_BOARD}
        interaction="interactive"
        size="fill"
        expandable
        locale={locale}
        messages={engineMessages(locale)}
        initialFilters={initialFilters}
        onFiltersChange={onFiltersChange}
        onNavigate={onNavigate}
        recordPanel={recordPanel}
      />
      {dialog}
    </div>
  );
}

/**
 * 「概览」, the console's home page: the overview's system board, embedded in
 * the interactive tier (rebuild proposal, batch 6) — the reader narrows the
 * window, presses into a panel and fills the screen with it, and nothing is
 * saved (D36). The due-for-retry panel carries the same row and bulk
 * commands as the failed executions' workbench (D39). Rearranging the board,
 * or saving one of their own, is the dashboard workbench's.
 */
export default function OverviewPage(props: BoardPageProps) {
  const { locale } = useI18n();
  return <LocalizedOverview key={locale} locale={locale} {...props} />;
}
