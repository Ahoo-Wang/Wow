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
 * An embedded view or dashboard (D22, the embedding half): a filter the page
 * fixed, as the bar reads it out.
 */
export const embedMessages = {
  'label.embed.locked': 'Set by this page',
  // Taken out for this page only: an embed never writes (D36).
  'label.embed.unavailable-removed':
    'Conditions its data source no longer offers are left out here; the saved view is unchanged.',
  'label.embed.locked-name': '{filter} (set by this page)',
  // A locked filter that holds nothing narrows nothing.
  'label.embed.any': 'Any',
} as const satisfies Record<string, string>;
