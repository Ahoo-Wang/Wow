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

import type { headerMessages } from '../header.js';

/**
 * `headerMessages` 的简体中文，逐键对应（`../header.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const headerZhCN = {
  // 标题栏与它下面的编辑带。
  'label.header.new-view': '尚未保存',
  'label.header.unsaved': '已修改',
  'label.header.unsaved-look': '只改了展示',
  'label.header.more': '更多视图操作',
  'label.header.copy-link': '复制链接',
  'label.header.link-copied': '链接已复制',
  'label.header.link-failed': '链接没能复制',
  'label.header.add-to-board': '放进仪表盘…',
  'label.board-pick.heading': '把「{title}」放进仪表盘',
  'label.board-pick.description':
    '它会放在仪表盘第一个标签页的第一个空位上，仪表盘随即保存。',
  'label.board-pick.loading': '正在读取仪表盘…',
  'label.board-pick.none': '没有你能修改的仪表盘。',
  'label.board-pick.of': '{board} · {definition}',
  'label.board-pick.cancel': '取消',
  'label.board-pick.added': '已放进「{board}」',
  'label.editor.pending': '{count} 项未应用',
} satisfies Record<keyof typeof headerMessages, string>;
