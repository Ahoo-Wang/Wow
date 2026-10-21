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
import { DatasetReading } from "./DatasetReading.tsx";
import { datasetOf } from "./datasetRecord.ts";

/**
 * The detail the engine opens a dataset in: the dataset read the console's
 * way (`DatasetReading`), in the engine's panel, which reads the whole
 * record, keeps the open one in the address and offers the dataset's actions.
 */
export const datasetDetail: RecordDetailOptions = {
  render: (context) => <DatasetReading {...context} />,
  title: (row) => {
    const state = datasetOf(row.data);
    return state.name
      ? (state.title ?? `${state.context}.${state.name}`)
      : undefined;
  },
};
