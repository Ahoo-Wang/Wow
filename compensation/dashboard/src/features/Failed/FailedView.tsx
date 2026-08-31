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
  const { t } = useI18n();
  const desktop = useMediaQuery("(min-width: 960px)");
  const { isOpen: isDrawerOpen } = useGlobalDrawer();
  const controller = useFailedQueueController({
    category,
    desktop,
    refreshPaused: isDrawerOpen,
  });

  const master = (
    <section
      className="flex h-full min-h-0 flex-col border-r bg-white"
      aria-label={t("Failed executions")}
    >
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
