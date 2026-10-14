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
import { ParkedEntryDetail } from "./ParkedEntryDetail.tsx";
import { parkedTitle } from "./parkedEntry.ts";

/**
 * The detail the engine opens a parked entry in (D60): the entry read the
 * console's way (`ParkedReading`) and its history (`ParkedHistory`) under
 * its subscription and aggregate, in the engine's panel, which reads the
 * whole record, keeps the open one in the address (`?id=`) and offers the
 * entry's actions.
 */
export const parkedDetail: RecordDetailOptions = {
  render: ({ row }) => <ParkedEntryDetail row={row} />,
  title: parkedTitle,
};
