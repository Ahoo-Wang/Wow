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

import type { RecordExtensions } from '../record/recordReactTypes.js';
import type { AnalysisExtensions } from '../analysis/analysisReactTypes.js';

import type { DashboardExtensions } from '../dashboard/dashboardReactTypes.js';

/** Page-level composition; each view kind consumes only its own extensions. */
export interface ViewExtensions extends RecordExtensions, AnalysisExtensions {
  dashboard?: DashboardExtensions;
}
