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

import { useSearchParams } from "react-router";
import { RefreshCw } from "lucide-react";
import { CallerGate } from "@/app/CallerGate.tsx";
import { useServed } from "@/app/served.tsx";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { problemText } from "@/views/catalogCommands.ts";
import { DATASET_PARAM } from "@/views/bindings.ts";
import { CONTROL } from "@/detail/parts.tsx";
import { Workbench } from "./Workbench.tsx";

/**
 * A served dataset's rows, read as any reader of it reads them: through the
 * node's query policies (D251), in the view engine's workbench. The dataset
 * open is in the address (`?dataset=`), the first served by default.
 */
export default function RowsPage() {
  const { t } = useI18n();
  const served = useServed();
  const [params, setParams] = useSearchParams();
  const asked = params.get(DATASET_PARAM);
  const open =
    served.datasets.find(({ id }) => id === asked)?.id ??
    served.datasets[0]?.id ??
    null;
  return (
    <CallerGate>
      {(caller) => (
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex items-center gap-2 border-b px-3 py-2 text-sm">
            <label className="flex items-center gap-2">
              <span className="text-muted-foreground">{t("dataset")}</span>
              <select
                className={CONTROL}
                value={open ?? ""}
                disabled={served.datasets.length === 0}
                onChange={(event) =>
                  setParams({ [DATASET_PARAM]: event.target.value })
                }
              >
                {served.datasets.map(({ id, entry }) => (
                  <option key={id} value={id}>
                    {entry.title ? `${entry.title} (${id})` : id}
                  </option>
                ))}
              </select>
            </label>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("reload")}
              title={t("reload")}
              disabled={served.loading}
              onClick={served.refresh}
            >
              <RefreshCw />
            </Button>
            {served.loading ? (
              <span className="text-muted-foreground">{t("loading")}</span>
            ) : null}
          </div>
          {served.error ? (
            <p role="alert" className="p-4 text-sm text-destructive">
              {t("datasetsFailed", { message: problemText(served.error) })}
            </p>
          ) : null}
          {open === null ? (
            served.loading ? null : (
              <p className="p-6 text-sm text-muted-foreground">
                {t("noDatasets")}
              </p>
            )
          ) : (
            <Workbench key={open} definitionId={open} caller={caller} />
          )}
        </div>
      )}
    </CallerGate>
  );
}
