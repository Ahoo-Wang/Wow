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

import { EmbeddedDashboard } from "@ahoo-wang/wow-view-engine/ui";
import { CallerGate } from "@/app/CallerGate.tsx";
import { callerKey } from "@/services/caller.ts";
import { useI18n } from "@/i18n.tsx";
import { OVERVIEW_BOARD, OVERVIEW_WORDS } from "@/views/overview.ts";

/**
 * The console's home page: the overview's system board, embedded in the
 * interactive tier, as the 9.x dashboard's overview — the reader narrows
 * the window, presses a number or a cluster into the workbench, fills the
 * screen with a panel, and refreshes it; nothing on it is saved.
 */
export default function OverviewPage() {
  const { locale } = useI18n();
  return (
    <CallerGate>
      {(caller) => (
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto fve:bg-canvas p-3">
          <h1 className="px-2.5 text-xl font-semibold">
            {OVERVIEW_WORDS[locale].title}
          </h1>
          <EmbeddedDashboard
            // Another caller reads other entries: the board starts over.
            key={callerKey(caller)}
            className="min-h-0 flex-1"
            instanceId={OVERVIEW_BOARD}
            interaction="interactive"
            size="fill"
            expandable
            withRefresh
          />
        </div>
      )}
    </CallerGate>
  );
}
