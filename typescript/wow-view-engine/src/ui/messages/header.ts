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
 * The title bar and the collapsible editor under it.
 *
 * The two title marks are keyed apart because they say different things: one
 * view was never saved, the other has been saved and edited since.
 */
export const headerMessages = {
  'label.header.new-view': 'Not saved yet',
  'label.header.unsaved': 'Edited',
  // Only the layout or the chart differs from what was saved (D23 Q15).
  'label.header.unsaved-look': 'Only the look changed',
  'label.header.more': 'More view actions',
  // 「复制链接」 in that menu, and what the press came to (R2-98).
  'label.header.copy-link': 'Copy link',
  'label.header.link-copied': 'Link copied',
  'label.header.link-failed': 'The link could not be copied',
  // 「放进仪表盘…」 in that menu, and its dialog (R2-98).
  'label.header.add-to-board': 'Add to dashboard…',
  'label.board-pick.heading': 'Add “{title}” to a dashboard',
  'label.board-pick.description':
    'It goes on the dashboard’s first tab, at the first free place, and the dashboard is saved.',
  'label.board-pick.loading': 'Reading the dashboards…',
  'label.board-pick.none': 'There is no dashboard you may change.',
  'label.board-pick.of': '{board} · {definition}',
  'label.board-pick.cancel': 'Cancel',
  'label.board-pick.added': 'Added to “{board}”',
  'label.editor.pending': '{count} not applied',
} as const satisfies Record<string, string>;
