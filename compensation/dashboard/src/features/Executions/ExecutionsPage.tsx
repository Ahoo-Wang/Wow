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
import type { ViewHandOver } from "@ahoo-wang/wow-view-engine";
import { DataWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { CircleAlert, ListFilter } from "lucide-react";
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
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

interface WorkbenchProps {
  /** A link's narrowing; left out, the view a way off handed the page. */
  handOver: ViewHandOver | undefined;
  searchParams: URLSearchParams;
  setSearchParams: SetURLSearchParams;
}

/**
 * The failed executions' workbench on the console's one engine: what an
 * execution offers — its commands, how it is read — is bound once to the
 * definition (`PageCommands`), so the page says only which view is open.
 */
function Workbench({
  handOver,
  searchParams,
  setSearchParams,
}: WorkbenchProps) {
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
    <DataWorkbench
      definitionId={EXECUTION_FAILED}
      instanceId={instanceId}
      onInstanceChange={onInstanceChange}
      handOver={handOver}
      // The console's shell already has the page's `main`.
      landmark="region"
    />
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
export default function ExecutionsPage() {
  const { t } = useI18n();
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
      <Workbench
        // A link's narrowing is in the address; a view a board sent here
        // (「在工作台中打开」, a press on a group) is in the history entry
        // it was opened on, where the engine reads it.
        handOver={(handed.key === key ? handed.handOver : null) ?? undefined}
        searchParams={searchParams}
        setSearchParams={setSearchParams}
      />
    </div>
  );
}
