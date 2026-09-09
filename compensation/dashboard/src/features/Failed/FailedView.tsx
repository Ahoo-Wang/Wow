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

import { useGlobalDrawer } from "@/components/GlobalDrawer";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import { FailedDetails } from "./details/FailedDetails.tsx";
import { FetchingFailedDetails } from "./details/FetchingFailedDetails.tsx";
import { FailedSearch } from "./FailedSearch.tsx";
import { FailedTable } from "./FailedTable.tsx";
import { FailedWorkspace } from "./FailedWorkspace.tsx";
import type { FindCategory } from "./FindCategory.ts";
import { useFailedQueueController } from "./useFailedQueueController.ts";
import { useSearchParams } from "react-router";
import { useMemo } from "react";
import { parseClusterScope, type ClusterScope } from "./clusterScope.ts";
import { formatDate } from "@/utils/dates.ts";
import { useI18n } from "@/i18n.tsx";

interface FailedViewProps {
  category: FindCategory;
}

function EmptyDetails() {
  const { t } = useI18n();
  return (
    <div className="flex h-full items-center justify-center bg-slate-50 p-8 text-center">
      <div>
        <p className="text-sm font-medium text-slate-700">
          {t("Select an execution")}
        </p>
        <p className="mt-1 text-xs text-slate-500">
          {t("Failure context and compensation actions will appear here.")}
        </p>
      </div>
    </div>
  );
}

function LoadingPageDetails() {
  const { t } = useI18n();
  return (
    <div
      role="status"
      aria-label={t("Loading page details")}
      className="flex h-full items-center justify-center bg-slate-50 p-8 text-center"
    >
      <div>
        <p className="text-sm font-medium text-slate-700">{t("Loading page")}</p>
        <p className="mt-1 text-xs text-slate-500">
          {t("The next executions will appear here shortly.")}
        </p>
      </div>
    </div>
  );
}

export default function FailedView({ category }: FailedViewProps) {
  const [params] = useSearchParams();
  const rawScope = params.get("cluster");
  const scope = useMemo(() => parseClusterScope(rawScope), [rawScope]);
  const { t } = useI18n();
  if (scope === undefined) {
    return (
      <div role="alert" className="p-6">
        {t("Invalid cluster filter.")}{" "}
        <a className="underline" href="/active">
          {t("Clear cluster filter")}
        </a>
      </div>
    );
  }
  return <FailedQueue key={rawScope ?? ""} category={category} scope={scope} />;
}

export function FailedQueue({
  category,
  scope,
}: FailedViewProps & { scope?: ClusterScope | null }) {
  const { t, locale } = useI18n();
  const desktop = useMediaQuery("(min-width: 960px)");
  const { isOpen: isDrawerOpen } = useGlobalDrawer();
  const controller = useFailedQueueController({
    category,
    scope,
    desktop,
    refreshPaused: isDrawerOpen,
  });

  const master = (
    <section
      className="flex h-full min-h-0 flex-col border-r bg-white"
      aria-label={t("Failed executions")}
    >
      {scope ? (
        <div className="border-b bg-muted/40 px-5 py-3 text-xs">
          <p className="font-medium">
            {t("Cluster filter")}: {scope.errorCode}
          </p>
          <p className="mt-1 break-words">
            {scope.contextName} / {scope.processorName} / {scope.functionName} ·{" "}
            {scope.functionKind}
          </p>
          <p className="mt-1">
            {formatDate(scope.start, undefined, locale)} –{" "}
            {formatDate(scope.end - 1, undefined, locale)}
          </p>
          <a
            className="mt-2 inline-block underline underline-offset-4"
            href="/active"
          >
            {t("Clear cluster filter")}
          </a>
        </div>
      ) : null}
      <FailedSearch
        key={controller.searchResetToken}
        onSearch={controller.onSearch}
        loading={controller.transitioning}
      />
      <FailedTable
        error={controller.blockingError}
        hasActiveFilters={controller.hasSearchFilters}
        loading={controller.transitioning}
        pagedList={controller.page}
        pageIndex={controller.displayedPageIndex}
        pageSize={controller.displayedPageSize}
        selectedId={controller.activeId}
        staleError={controller.staleError}
        onPaginationChange={controller.onPaginationChange}
        onClearFilters={controller.clearFilters}
        onRetry={controller.refresh}
        onSelect={controller.select}
      />
    </section>
  );

  const details = controller.suspendingSelection ? (
    <LoadingPageDetails />
  ) : controller.selectedState ? (
    <FailedDetails
      state={controller.selectedState}
      mutationsDisabled={controller.mutationsDisabled}
      onChanged={controller.refresh}
    />
  ) : controller.selectedId ? (
    <FetchingFailedDetails
      key={controller.selectedId}
      id={controller.selectedId}
      refreshToken={controller.refreshToken}
      mutationsDisabled={controller.mutationsDisabled}
      onChanged={controller.refresh}
    />
  ) : (
    <EmptyDetails />
  );

  return (
    <FailedWorkspace
      desktop={desktop}
      details={details}
      detailsOpen={Boolean(controller.selectedId)}
      master={master}
      onCloseDetails={controller.clearSelection}
    />
  );
}
