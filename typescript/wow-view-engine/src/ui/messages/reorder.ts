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
 * What every list that can be put in another order says the same way: how
 * its handle is worked, and the four places its menu offers (the one-click
 * way to move a row, for a pointer that cannot drag — WCAG 2.5.7).
 *
 * One family for every list, because the handle is one component
 * (`DragHandle`) and a reader who learned it in the column settings should
 * hear the same sentence on a funnel's stages. What differs per list — the
 * handle's name, what a pick-up and a landing say — stays in that list's
 * own family, since those name the thing being carried.
 */
export const reorderMessages = {
  'label.reorder.instructions':
    'Press the arrow keys to move it one place. Press Space to pick it up, the arrow keys to move it, Space again to put it down, or Escape to leave it where it was. Or press the handle to choose where it goes.',
  'label.reorder.first': 'Move to the start',
  'label.reorder.earlier': 'Move one place earlier',
  'label.reorder.later': 'Move one place later',
  'label.reorder.last': 'Move to the end',
} as const;
