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
import { useSearchParams } from "react-router";
import { DatasetWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { CallerGate } from "@/app/CallerGate.tsx";
import { useServed } from "@/app/served.tsx";
import { callerKey } from "@/services/caller.ts";
import { DATASET_PARAM, VIEW_PARAM } from "@/views/bindings.ts";

/**
 * A served dataset's rows, read as any reader of it reads them: through the
 * node's query policies (D251), in the view engine's standalone dataset view
 * (`DatasetWorkbench`, D238 ⑨). The dataset and the view open are in the
 * address (`?dataset=`, `?view=`); a row offers the commands of the
 * aggregates it refers to (`referenceActions`).
 */
export default function RowsPage() {
  const served = useServed();
  const [params, setParams] = useSearchParams();
  const onDatasetChange = useCallback(
    (dataset: string) => setParams({ [DATASET_PARAM]: dataset }),
    [setParams],
  );
  const onInstanceChange = useCallback(
    (view: string | null) =>
      setParams((current) => {
        const next = new URLSearchParams(current);
        if (view === null) next.delete(VIEW_PARAM);
        else next.set(VIEW_PARAM, view);
        return next;
      }),
    [setParams],
  );
  return (
    <CallerGate>
      {(caller) => (
        <div className="flex min-h-0 flex-1 flex-col">
          <DatasetWorkbench
            // Another caller reads other rows: the workbench starts over.
            key={callerKey(caller)}
            // It says while they are read, and why a read failed.
            datasets={served}
            datasetId={params.get(DATASET_PARAM)}
            onDatasetChange={onDatasetChange}
            instanceId={params.get(VIEW_PARAM)}
            onInstanceChange={onInstanceChange}
            landmark="region"
          />
        </div>
      )}
    </CallerGate>
  );
}
