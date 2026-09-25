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
import { useSearchParams, type SetURLSearchParams } from "react-router";
import type {
  ViewHandOver,
  ViewSource,
  ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { useViewEngine } from "@ahoo-wang/wow-view-engine/react";
import {
  DataWorkbench,
  zhCN,
  type DataWorkbenchProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { CircleAlert, ListFilter } from "lucide-react";
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useI18n, type Locale } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import { executionEngineOptions, localViewStore } from "@/views/engine.ts";
import { useExecutionDetail } from "./detail/useExecutionDetail.tsx";
import { useExecutionActions } from "./useExecutionActions.tsx";
import {
  executionCommands,
  type ExecutionCommands,
} from "./executionCommands.ts";
import {
  CLUSTER_PARAM,
  END_PARAM,
  executionsView,
  readLinkScope,
  SCOPE_PARAMS,
  START_PARAM,
  VIEW_PARAM,
  type LinkScope,
} from "./linkScope.ts";

export { VIEW_PARAM } from "./linkScope.ts";

type Messages = NonNullable<DataWorkbenchProps["messages"]>;

/**
 * Saved views live in this browser until the Wow storage backend (stage 6);
 * the view list and the save dialog say so, so nobody expects a colleague to
 * see them.
 */
const MESSAGES: Record<Locale, Messages> = {
  en: {
    "label.scope.group.personal": "My views (this browser)",
    "label.scope.personal.description":
      "Saved in this browser on this computer. Only you see it.",
  },
  "zh-CN": {
    ...zhCN,
    "label.scope.group.personal": "我的视图（本机）",
    "label.scope.personal.description":
      "存在这台电脑的这个浏览器里，只有你看得到。",
  },
};

export interface ExecutionsPageProps {
  /** For tests: the store the engines read and write. */
  store?: ViewStore;
  /** For tests: where the rows come from instead of the service. */
  source?: ViewSource;
  /** For tests: where an execution's history comes from instead. */
  historySource?: ViewSource;
  /** For tests: what the row and bulk commands send instead. */
  commands?: ExecutionCommands;
}

interface LocalizedWorkbenchProps extends ExecutionsPageProps {
  locale: Locale;
  store: ViewStore;
  commands: ExecutionCommands;
  handOver: ViewHandOver | null;
  searchParams: URLSearchParams;
  setSearchParams: SetURLSearchParams;
}

/**
 * One engine in one language, for as long as the language holds: the
 * definition's labels are in one language (G12), so the page keys this by
 * language and a change of language builds a new engine over the same store,
 * disposing the old one on unmount.
 */
function LocalizedWorkbench({
  locale,
  store,
  source,
  historySource,
  commands,
  handOver,
  searchParams,
  setSearchParams,
}: LocalizedWorkbenchProps) {
  const engine = useViewEngine(
    executionEngineOptions({ locale, store, source, historySource }),
  );
  const { actions, bulk, dialog } = useExecutionActions(commands);
  const messages = MESSAGES[locale];
  const detail = useExecutionDetail({ engine, commands, locale, messages });
  const instanceId = searchParams.get(VIEW_PARAM);

  const onInstanceChange = useCallback(
    (id: string | null) => {
      if (id === instanceId) return;
      setSearchParams((current) => {
        const next = new URLSearchParams(current);
        if (id === null) next.delete(VIEW_PARAM);
        else next.set(VIEW_PARAM, id);
        // A link's narrowing belongs to the view it opened; the workbench
        // lets it go with that view, and so does the address. The view it
        // opened is reported too when the address named none.
        const narrowed =
          handOver?.kind === "view" && handOver.instanceId === id;
        if (!narrowed) for (const param of SCOPE_PARAMS) next.delete(param);
        return next;
      });
    },
    [handOver, instanceId, setSearchParams],
  );

  return (
    <>
      <DataWorkbench
        engine={engine}
        definitionId={EXECUTION_FAILED}
        instanceId={instanceId}
        onInstanceChange={onInstanceChange}
        handOver={handOver}
        // The console's shell already has the page's `main`.
        landmark="region"
        locale={locale}
        messages={messages}
        record={{ actions, bulk, detail }}
      />
      {dialog}
    </>
  );
}

/** The key a link's narrowing is told apart by: its raw parameters. */
function scopeKey(params: URLSearchParams): string {
  return JSON.stringify(SCOPE_PARAMS.map((param) => params.get(param)));
}

interface Handed {
  key: string;
  handOver: ViewHandOver | null;
}

/**
 * What the workbench is handed for a link's narrowing: the view the link
 * names, under the narrowing as its scope (`handOver`) — shown on the
 * applied bar and nobody's to take off there. A new object only when the
 * narrowing changes: taken off here (the view stays, unnarrowed), or gone
 * with the view the reader left, which the workbench has already let go.
 */
function nextHandOver(
  scope: LinkScope,
  instanceId: string | null,
  previous: ViewHandOver | null,
): ViewHandOver | null {
  if (scope.kind === "scoped")
    return {
      kind: "view",
      definitionId: EXECUTION_FAILED,
      instanceId: instanceId ?? executionsView("active"),
      scopeFilter: scope.filter,
      filter: null,
    };
  if (
    previous?.kind === "view" &&
    previous.scopeFilter !== null &&
    previous.instanceId === instanceId
  )
    return { ...previous, scopeFilter: null };
  return null;
}

/** Takes `params` off the address, and nothing else. */
function without(
  setSearchParams: SetURLSearchParams,
  params: readonly string[],
) {
  setSearchParams((current) => {
    const next = new URLSearchParams(current);
    for (const param of params) next.delete(param);
    return next;
  });
}

/**
 * 「失败执行」: the failed executions in the view engine's workbench, whose
 * view list holds the old console's seven queues as system views beside the
 * reader's own (rebuild proposal, Q3). The open view is the `view` search
 * parameter, so a view is a link; the old queue addresses redirect here.
 * A link's `cluster` or `start`/`end` narrows the view it opens, as its
 * scope; a malformed one is said rather than widened to every record.
 */
export default function ExecutionsPage({
  store,
  source,
  historySource,
  commands,
}: ExecutionsPageProps) {
  const { locale, t } = useI18n();
  const [sent] = useState(() => commands ?? executionCommands());
  const [searchParams, setSearchParams] = useSearchParams();
  const instanceId = searchParams.get(VIEW_PARAM);
  const key = scopeKey(searchParams);
  const scope = readLinkScope(searchParams);
  const [handed, setHanded] = useState<Handed>(() => ({
    key,
    handOver: nextHandOver(scope, instanceId, null),
  }));
  // Derived while rendering, from the last narrowing handed: the address is
  // the one source of it, and each change of it hands the workbench once.
  if (handed.key !== key)
    setHanded({
      key,
      handOver: nextHandOver(scope, instanceId, handed.handOver),
    });

  if (scope.kind === "invalid") {
    const cluster = scope.parameter === "cluster";
    return (
      <div className="executions-page">
        <Alert variant="destructive" className="executions-page-note">
          <CircleAlert />
          <AlertTitle>
            {cluster
              ? t("Invalid cluster filter.")
              : t("Invalid time range filter.")}
          </AlertTitle>
          <AlertDescription>
            {t("The link's condition cannot be read, so no records are shown.")}
          </AlertDescription>
          <AlertAction>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() =>
                without(
                  setSearchParams,
                  cluster ? [CLUSTER_PARAM] : [START_PARAM, END_PARAM],
                )
              }
            >
              {cluster
                ? t("Clear cluster filter")
                : t("Clear time range filter")}
            </Button>
          </AlertAction>
        </Alert>
      </div>
    );
  }

  return (
    <div className="executions-page">
      {scope.kind === "scoped" ? (
        <Alert className="executions-page-note">
          <ListFilter />
          <AlertDescription>
            {t("This view is narrowed by the link it was opened from.")}
          </AlertDescription>
          <AlertAction>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => without(setSearchParams, SCOPE_PARAMS)}
            >
              {t("Remove the narrowing")}
            </Button>
          </AlertAction>
        </Alert>
      ) : null}
      <LocalizedWorkbench
        key={locale}
        locale={locale}
        store={store ?? localViewStore()}
        source={source}
        historySource={historySource}
        commands={sent}
        handOver={handed.key === key ? handed.handOver : null}
        searchParams={searchParams}
        setSearchParams={setSearchParams}
      />
    </div>
  );
}
