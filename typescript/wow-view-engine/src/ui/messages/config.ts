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

/** Shared config — the part every view kind stores, so every kind reports it. */
export const configMessages = {
  'config.refresh.missing': 'This view has no refresh setting.',
  'config.refresh.not-an-integer':
    'The refresh interval must be whole seconds.',
  'config.refresh.too-long':
    'The refresh interval cannot exceed {max} seconds.',
  'config.refresh.too-short':
    'The refresh interval must be at least {min} seconds.',
  'config.filter.invalid': 'The conditions of this view could not be read.',
  'config.filterMode.unknown': 'This view has an unknown filter mode.',
  'config.filterMode.not-simple':
    'These conditions need the advanced editor to be shown in full.',
  'config.invalid': 'This view could not be read.',
} as const satisfies Record<string, string>;
