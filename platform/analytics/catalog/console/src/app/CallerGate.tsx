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
import { Ban, Eye, UserRound } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useI18n, type Message } from "@/i18n.tsx";
import {
  isApprover,
  isEditor,
  isReader,
  type CatalogCaller,
  type SignedOut,
} from "@/services/caller.ts";
import { CallerForm } from "./CallerForm.tsx";
import { useCaller, useSettingsState, useSignedOut } from "./services.tsx";

/** What the sign-in page says of why the console was signed out. */
const SIGNED_OUT = {
  expired: "signedOutExpired",
  refused: "signedOutRefused",
  "headers-refused": "signedOutHeaders",
} as const satisfies Record<SignedOut, Message>;

/**
 * A page that reads the catalog: without a caller there is nothing to read
 * — the service answers 401 — so it asks for one first, saying why it was
 * signed out if it was. Then it waits for the catalog's settings, which
 * decide the views and actions it offers. A caller with none of the
 * catalog's roles is told it reads nothing; one with neither
 * `analytics.catalog.editor` (or admin) nor `analytics.catalog.approver`,
 * that it only reads.
 */
export function CallerGate({
  children,
}: {
  children: (caller: CatalogCaller) => ReactNode;
}) {
  const { t } = useI18n();
  const caller = useCaller();
  const signedOut = useSignedOut();
  const settings = useSettingsState();

  if (!caller)
    return (
      <div className="flex flex-col items-center gap-4 overflow-auto p-6">
        <Alert className="max-w-md">
          <UserRound />
          <AlertDescription>
            {/* Why the console asks again, before what it needs. */}
            {signedOut ? (
              <span className="block font-medium" data-testid="signed-out">
                {t(SIGNED_OUT[signedOut])}
              </span>
            ) : null}
            {t("needCaller")}
          </AlertDescription>
        </Alert>
        <CallerForm />
      </div>
    );

  if (settings.status === "reading")
    return (
      <p role="status" className="p-6 text-sm text-muted-foreground">
        {t("readingSettings")}
      </p>
    );

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {!isReader(caller) ? (
        <Alert className="mx-3 mt-3 w-auto" data-testid="no-role">
          <Ban />
          <AlertDescription>{t("noRole")}</AlertDescription>
        </Alert>
      ) : isEditor(caller) || isApprover(caller) ? null : (
        <Alert className="mx-3 mt-3 w-auto">
          <Eye />
          <AlertDescription>{t("readOnly")}</AlertDescription>
        </Alert>
      )}
      {children(caller)}
    </div>
  );
}
