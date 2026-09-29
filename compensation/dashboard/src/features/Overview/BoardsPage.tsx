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

import { DashboardWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { OVERVIEW } from "@/views/overview.ts";

/**
 * The dashboard workbench: the overview board and the reader's own boards
 * beside it, built and saved here — in this browser until the Wow storage
 * backend (stage 6). The home page's 「在工作台中打开」 opens the overview
 * here; the board to open, and the filters it was left under, are the
 * address's, as the engine keeps it.
 */
export default function BoardsPage() {
  return (
    <div className="boards-page">
      {/* The console's shell already has the page's `main`. */}
      <DashboardWorkbench definitionId={OVERVIEW} landmark="region" />
    </div>
  );
}
