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
 * The view manager.
 *
 * Renaming and deleting are keyed apart from the save commands although two
 * of them read the same: these are the accessible names of icon buttons on a
 * row, and a translation may well need a different word for "rename this one"
 * than for "rename what is open".
 */
export const manageMessages = {
  'label.manage.open': 'Manage views',
  'label.manage.heading': 'Manage views',
  'label.manage.description':
    'Rename, reorder and delete views, and choose which one opens first.',
  'label.manage.default': 'Default',
  // What is to become of a view, as against where it sits in the list — the
  // latter is the handle a row is dragged by, which needs no group of its own.
  'label.manage.view-group': 'What to do with this view',
  'label.manage.set-default': 'Open this one first',
  'label.manage.unset-default': 'Stop opening this one first',
  // Where a view sits in the list. The order is the user's, and it is made by
  // carrying a row rather than by clicking it up one step at a time; the
  // keyboard says the same thing on the same handle.
  'label.manage.drag': 'Reorder {title}',
  'label.manage.instructions':
    'Press the arrow keys to move this view one place. Press space to pick it up, the arrow keys to move it, space again to drop it and escape to cancel.',
  'label.manage.moved': '{title} moved to position {index} of {total}',
  'label.manage.picked': '{title} picked up',
  'label.manage.cancelled': 'Move cancelled; {title} stayed where it was',
  'label.manage.rename': 'Rename',
  'label.manage.rename-confirm': 'Save the title',
  'label.manage.rename-cancel': 'Keep the title',
  'label.manage.delete': 'Delete',
  // A preference conflict that was reloaded keeps what the user meant and
  // puts it to them once more (design/management.md), so the button offers the write
  // again rather than a recovery of the one that lost.
  'label.manage.resubmit': 'Apply again',
  'label.manage.reload': 'Reload list',
} as const satisfies Record<string, string>;
