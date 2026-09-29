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

import type { QueryModelDescriptor } from "@ahoo-wang/wow-client";
import mongoEvent from "../../e2e/support/descriptors/mongo-event.json";
import mongoSnapshot from "../../e2e/support/descriptors/mongo-snapshot.json";

/**
 * The compensation service's query descriptors, as committed snapshots
 * (`execution_failed/snapshot/schema`, `execution_failed/event/schema`,
 * host-integration.md 3, Q2): what the definitions are built from when the
 * module loads (`defineView`). The descriptor a deployment answers narrows
 * them again at run time, so a MongoDB store without a text index still
 * offers no error search (G15). The same files are what the e2e suite's
 * service answers.
 */
export const EXECUTION_FAILED_DESCRIPTOR =
  mongoSnapshot as unknown as QueryModelDescriptor;

export const EXECUTION_HISTORY_DESCRIPTOR =
  mongoEvent as unknown as QueryModelDescriptor;
