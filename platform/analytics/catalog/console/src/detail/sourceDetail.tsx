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
import { SourceReading } from "./SourceReading.tsx";
import { sourceOf } from "./sourceRecord.ts";

/**
 * The detail the engine opens a source in: the source read the console's
 * way (`SourceReading`), in the engine's panel, which reads the whole record,
 * keeps the open one in the address and offers the source's actions.
 */
export const sourceDetail: RecordDetailOptions = {
  render: (context) => <SourceReading {...context} />,
  title: (row) => {
    const state = sourceOf(row.data);
    return state.table ? `${state.database}.${state.table}` : undefined;
  },
};
