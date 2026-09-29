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

import { DataWorkbench } from "@ahoo-wang/wow-view-engine/ui";
import { EXECUTION_HISTORY } from "@/views/executionHistory.ts";

/**
 * The failed executions' event streams on a workbench of their own: where a
 * board's outcome figures open — 「在工作台中打开」 on a panel, or a press on
 * one of its days — and where the reader keeps asking of the streams: which
 * commands were appended when, and by which execution. The open view is
 * the address's, as the engine keeps it.
 */
export default function EventsPage() {
  return (
    <div className="executions-page">
      <DataWorkbench definitionId={EXECUTION_HISTORY} landmark="region" />
    </div>
  );
}
