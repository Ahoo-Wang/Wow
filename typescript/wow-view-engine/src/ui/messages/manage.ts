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
  // The two things a row's buttons are for, and the reason there are two
  // groups rather than one strip of five: where a view sits in the list, and
  // what is to become of it.
  'label.manage.order-group': 'Order in the list',
  'label.manage.view-group': 'What to do with this view',
  'label.manage.set-default': 'Open this one first',
  'label.manage.unset-default': 'Stop opening this one first',
  'label.manage.move-up': 'Move up',
  'label.manage.move-down': 'Move down',
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
