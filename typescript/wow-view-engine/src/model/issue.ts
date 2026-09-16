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

/**
 * A validation finding. Every kernel reports problems this way.
 *
 * Wording lives in the UI layer: `code` is the branching key and `params`
 * carries the values a message needs, so the model stays free of copy.
 */
export interface Issue {
  /** Stable machine-readable key, e.g. `record.sort.field-not-sortable`. */
  code: string;
  severity: IssueSeverity;
  /** Location inside the config, e.g. `['sort', 0, 'field']`. */
  path: IssuePath;
  params?: Record<string, string | number>;
}

/** `error` blocks apply and save; `warning` is reported without blocking. */
export type IssueSeverity = 'error' | 'warning';

export type IssuePath = (string | number)[];
