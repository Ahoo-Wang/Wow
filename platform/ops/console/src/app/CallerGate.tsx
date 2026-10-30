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

import type { ReactNode } from "react";
import { Eye, EyeOff, UserRound } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useI18n } from "@/i18n.tsx";
import { isOperator, isReader, type OpsCaller } from "@/services/caller.ts";
import { CallerForm } from "./CallerForm.tsx";
import { useCaller } from "./services.tsx";

/**
 * A page that reads the entries: without a caller there is nothing to read
 * — the service answers 401 — so it asks for one first; a caller without
 * `ops.operator` is told it only reads, one with neither `ops.viewer` nor
 * `ops.operator` that the service shows it nothing (403).
 */
export function CallerGate({
  children,
}: {
  children: (caller: OpsCaller) => ReactNode;
}) {
  const { t } = useI18n();
  const caller = useCaller();

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
      {isOperator(caller) ? null : isReader(caller) ? (
        <Alert className="mx-3 mt-3 w-auto">
          <Eye />
          <AlertDescription>{t("readOnly")}</AlertDescription>
        </Alert>
      ) : (
        <Alert className="mx-3 mt-3 w-auto">
          <EyeOff />
          <AlertDescription>{t("noReader")}</AlertDescription>
        </Alert>
      )}
      {children(caller)}
    </div>
  );
}
