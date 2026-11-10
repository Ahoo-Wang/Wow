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

import type { manageMessages } from '../manage.js';

/**
 * `manageMessages` 的简体中文，逐键对应（`../manage.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const manageZhCN = {
  // 管理视图。
  'label.manage.open': '管理视图',
  'label.manage.heading': '管理视图',
  'label.manage.description': '改名、排序、删除视图，并选择默认打开的视图。',
  'label.manage.default': '默认',
  'label.manage.tab.views': '视图',
  'label.manage.tab.wording': '措辞',
  'label.wording.description':
    '读者看到的数据集名称、记录的叫法与字段名。留空就用数据集自己的叫法；改动对所有读者生效，在之后打开的视图里。',
  'label.wording.title': '标题',
  'label.wording.noun': '一条记录叫作',
  'label.wording.fields': '字段',
  'label.wording.field.label': '「{field}」的名称',
  'label.wording.field.shown': '显示「{field}」',
  'label.wording.field.up': '上移「{field}」',
  'label.wording.field.down': '下移「{field}」',
  'label.wording.save': '保存措辞',
  'label.wording.saved': '已保存。之后打开的视图使用新的措辞。',
  'label.wording.failed': '措辞没能保存，请重试。',
  'label.manage.view-group': '「{title}」的操作',
  'label.manage.set-default': '设为默认',
  'label.manage.unset-default': '取消默认',
  'label.manage.drag': '拖动排序：{title}',
  'label.manage.moved': '{title} 已移到第 {index} 位，共 {total} 位',
  'label.manage.picked': '已拿起 {title}',
  'label.manage.cancelled': '移动已取消，{title} 留在原处',
  'label.manage.rename': '改名',
  'label.manage.rename-of': '改名 {title}',
  'label.manage.rename-confirm': '保存标题',
  'label.manage.rename-cancel': '保留原标题',
  'label.manage.delete': '删除',
  'label.manage.share': '设为共享',
  'label.manage.make-personal': '设为个人',
  'label.manage.shared': '{title} 已设为共享',
  'label.manage.made-personal': '{title} 已设为个人',
  'label.manage.publish': '发布为系统视图',
  'label.manage.published': '{title} 已发布为系统视图',
  'label.manage.resubmit': '再应用一次',
  'label.manage.reload': '重新加载列表',
} satisfies Record<keyof typeof manageMessages, string>;
