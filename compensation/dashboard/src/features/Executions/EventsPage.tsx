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
import { useLocation, useSearchParams } from "react-router";
import type { ViewSource, ViewStore } from "@ahoo-wang/wow-view-engine";
import { useViewEngine } from "@ahoo-wang/wow-view-engine/react";
import { DataWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { useI18n, type Locale } from "@/i18n.tsx";
import { executionEngineOptions, localViewStore } from "@/views/engine.ts";
import { EXECUTION_HISTORY } from "@/views/executionHistory.ts";
import { engineMessages } from "@/views/messages.ts";
import {
  VIEW_PARAM,
  navigationState,
  useViewNavigation,
} from "@/views/navigation.ts";

export interface EventsPageProps {
  /** For tests: the store the engines read and write. */
  store?: ViewStore;
  /** For tests: where the event streams come from instead of the service. */
  historySource?: ViewSource;
}

function LocalizedEvents({
  locale,
  store,
  historySource,
}: EventsPageProps & { locale: Locale }) {
  const engine = useViewEngine(
    executionEngineOptions({
      locale,
      store: store ?? localViewStore(),
      historySource,
    }),
  );
  const [searchParams, setSearchParams] = useSearchParams();
  const handOver = navigationState(useLocation().state).handOver ?? null;
  const instanceId = searchParams.get(VIEW_PARAM);
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
    <DataWorkbench
      engine={engine}
      definitionId={EXECUTION_HISTORY}
      instanceId={instanceId}
      onInstanceChange={onInstanceChange}
      handOver={handOver}
      onNavigate={onNavigate}
      landmark="region"
      locale={locale}
      messages={engineMessages(locale)}
    />
  );
}

/**
 * The failed executions' event streams on a workbench of their own: where a
 * board's outcome figures open — 「在工作台中打开」 on a panel, or a press on
 * one of its days — and where the reader keeps asking of the streams: which
 * commands were appended when, and by which execution.
 */
export default function EventsPage(props: EventsPageProps) {
  const { locale } = useI18n();
  return (
    <div className="executions-page">
      <LocalizedEvents key={locale} locale={locale} {...props} />
    </div>
  );
}
