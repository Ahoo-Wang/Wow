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

import { Link, useLocation } from "react-router";
import { LayoutDashboard } from "lucide-react";
import {
  EmbeddedDashboard,
  type ViewRouteState,
} from "@ahoo-wang/wow-view-engine/ui";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { OVERVIEW_BOARD } from "@/views/overview.ts";
import { BOARDS_PATH, withView } from "@/views/routes.ts";

/**
 * 「概览」, the console's home page: the overview's system board, embedded in
 * the interactive tier (rebuild proposal, batch 6) — the reader narrows the
 * window, presses into a panel, fills the screen with it, and reads when the
 * numbers were read with a refresh beside it (`withRefresh`, as the old
 * home page's header had), and nothing is saved (D36). The due-for-retry
 * panel carries the same row and bulk commands as the failed executions'
 * workbench (D39): they are bound to the definition (`PageCommands`).
 * Rearranging the board, or saving one of their own, is the dashboard
 * workbench's.
 */
export default function OverviewPage() {
  const { t } = useI18n();
  // The filters the reader left the board under, which the engine keeps in
  // the history entry: the workbench opens the board under them too.
  const { filters } = (useLocation().state ?? {}) as ViewRouteState;

  return (
    // The page stands on the board's own ground (`bg-canvas`), header and
    // all: a white strip over a grey board read as two pages (D59). The
    // header's edges line up with the panels': the page's 12px and the
    // board's inset of 10px.
    <div className="flex min-h-0 flex-1 flex-col gap-3 bg-canvas p-3">
      <div className="flex flex-wrap items-center justify-between gap-2 px-2.5">
        {/* The page's heading: the top bar names the place, not the page, and
            the board has no workbench to title it (console-redesign.md §5.1). */}
        <h1 className="text-xl font-semibold">{t("Overview")}</h1>
        <Button
          variant="outline"
          size="sm"
          render={
            <Link
              to={withView(BOARDS_PATH, OVERVIEW_BOARD)}
              state={{ filters }}
            />
          }
        >
          <LayoutDashboard data-icon="inline-start" />
          {t("Open in the dashboard workbench")}
        </Button>
      </div>
      <EmbeddedDashboard
        className="min-h-0 flex-1"
        instanceId={OVERVIEW_BOARD}
        interaction="interactive"
        size="fill"
        expandable
        withRefresh
      />
    </div>
  );
}
