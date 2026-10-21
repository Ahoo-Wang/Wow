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
import { DashboardWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { CallerGate } from "@/app/CallerGate.tsx";
import { callerKey } from "@/services/caller.ts";
import { BOARDS } from "@/views/boards.ts";
import { VIEW_PARAM } from "@/views/bindings.ts";

/**
 * The boards over the datasets (D238 ⑨): the view engine's dashboard
 * workbench over `analytics-catalog-boards`, whose panels show views of any
 * dataset served, of whichever service. The open board is in the address
 * (`?view=`); a panel opens its view on the rows page.
 */
export default function BoardsPage() {
  const [params, setParams] = useSearchParams();
  const onInstanceChange = useCallback(
    (board: string | null) =>
      setParams((current) => {
        const next = new URLSearchParams(current);
        if (board === null) next.delete(VIEW_PARAM);
        else next.set(VIEW_PARAM, board);
        return next;
      }),
    [setParams],
  );
  return (
    <CallerGate>
      {(caller) => (
        <div className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1">
          <DashboardWorkbench
            // Another caller reads other datasets: the workbench starts over.
            key={callerKey(caller)}
            definitionId={BOARDS}
            instanceId={params.get(VIEW_PARAM)}
            onInstanceChange={onInstanceChange}
            landmark="region"
          />
        </div>
      )}
    </CallerGate>
  );
}
