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

import type { RecordDetailOptions } from "@ahoo-wang/wow-view-engine/ui";
import type { ExecutionCommands } from "../executionCommands.ts";
import { ExecutionReading } from "./ExecutionReading.tsx";
import { executionTitle } from "./executionState.ts";

/**
 * The failed executions' record detail (D60): the execution read the
 * console's way (`ExecutionReading`) under the handler's name, in the
 * engine's panel. Which execution is open is the address's `?id=`, which the
 * engine keeps (`ViewHost`'s router), so a link opens it.
 */
export function executionDetail(
  commands: ExecutionCommands,
): RecordDetailOptions {
  return {
    render: ({ row, complete, refresh }) => (
      <ExecutionReading
        row={row}
        complete={complete}
        refresh={refresh}
        commands={commands}
      />
    ),
    title: executionTitle,
  };
}
