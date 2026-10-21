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
 * The standalone dataset view (D93): the picker of the datasets served, and
 * what it says while none is.
 */
export const datasetMessages = {
  'label.dataset.picker': 'Dataset',
  'label.dataset.none': 'No dataset yet',
  'label.dataset.none-hint': 'A dataset appears here once a catalog serves it.',
  'label.dataset.loading': 'Reading the datasets…',
  'label.dataset.refresh': 'Read the datasets again',
} as const satisfies Record<string, string>;
