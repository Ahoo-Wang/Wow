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

import { dequal } from 'dequal';
import type { ViewConfig, ViewInstance } from '../model/index.js';

/**
 * Whether the conditions in force are other than the ones the view was saved
 * with — `null` when it was never saved, or is not a view of records with
 * conditions of its own. The empty result reads it to choose its way out:
 * back to the saved conditions, or on to another question (`emptyWayOut`).
 */
export function conditionsDrifted(
  applied: ViewConfig,
  saved: ViewInstance | null,
): boolean | null {
  const baseline = saved?.config;
  if (applied.kind !== 'record' || baseline?.kind !== 'record') return null;
  return !dequal(applied.filter, baseline.filter);
}
