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
import { DataWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { Eye, UserRound } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { CallerForm } from "@/app/CallerForm.tsx";
import { useCaller } from "@/app/services.tsx";
import { useI18n } from "@/i18n.tsx";
import { isOperator } from "@/services/caller.ts";
import { VIEW_PARAM } from "@/views/bindings.ts";
import { PARKED } from "@/views/parked.ts";

/**
 * The parked entries in the view engine's workbench: the operator's queues
 * as system views beside the operator's own, the open one in the address
 * (`?view=`), an entry's detail and the actions on it in the engine's
 * panel. Without a caller there is nothing to read — the service answers
 * 401 — so the page asks for one first.
 */
export default function ParkedPage() {
  const { t } = useI18n();
  const caller = useCaller();
  const [params, setParams] = useSearchParams();
  const instanceId = params.get(VIEW_PARAM);
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

  if (!caller)
    return (
      <div className="flex flex-col items-center gap-4 overflow-auto p-6">
        <Alert className="max-w-md">
          <UserRound />
          <AlertDescription>{t("needCaller")}</AlertDescription>
        </Alert>
        <CallerForm />
      </div>
    );

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {isOperator(caller) ? null : (
        <Alert className="mx-3 mt-3 w-auto">
          <Eye />
          <AlertDescription>{t("readOnly")}</AlertDescription>
        </Alert>
      )}
      <div className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1">
        <DataWorkbench
          // Another caller reads other entries: the workbench starts over.
          key={`${caller.tenant}/${caller.user}/${caller.roles.join(",")}`}
          definitionId={PARKED}
          instanceId={instanceId}
          onInstanceChange={onInstanceChange}
          landmark="region"
        />
      </div>
    </div>
  );
}
