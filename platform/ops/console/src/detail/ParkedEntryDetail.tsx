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

import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import { useServices } from "@/app/services.tsx";
import { ParkedHistory } from "./ParkedHistory.tsx";
import { ParkedReading } from "./ParkedReading.tsx";

/** An entry's reading with its history, read through the console's services. */
export function ParkedEntryDetail({ row }: { row: RecordRow }) {
  const { history } = useServices();
  return (
    <ParkedReading
      row={row}
      history={
        <ParkedHistory
          id={String(row.key)}
          // The open entry read again (the queues' 15 seconds, an action's
          // snapshot): its history is read again with it.
          revision={row.data.version}
          source={history}
        />
      }
    />
  );
}
