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

import type { exportMessages } from '../export.js';

/**
 * `exportMessages` 的简体中文，逐键对应（`../export.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const exportZhCN = {
  // 导出窗口：两种口径各带条数，先摆清文件里会有什么，再跑、再说结果（D14）。
  'label.export.title': '导出',
  'label.export.currency-column': '{field}（币种）',
  'label.export.data': '导出数据…',
  'label.export.image-png': '导出图片（PNG）',
  'label.export.image-svg': '导出图片（SVG）',
  'label.export.image-failed': '图片没有生成。请再试一次，或导出为 SVG。',
  'label.export.image-name': '图表',
  'label.export.description': '结果将导出为一个 CSV 文件。',
  'label.export.scope': '导出哪些记录',
  'label.export.selected': '选中（{count}）',
  'label.export.all': '所有（{count}，按当前筛选）',
  'label.export.all-unknown': '所有（按当前筛选）',
  'label.export.rows': '{count} 条记录',
  'label.export.rows-one': '1 条记录',
  'label.export.rows-unknown': '按当前条件匹配到的记录',
  'label.export.conditions': '条件：{conditions}',
  'label.export.columns': '{count} 列：{names}',
  'label.export.columns-one': '1 列：{names}',
  'label.export.file': '文件：{name}',
  'label.export.over-limit': '超过上限 {max} 条，文件只会有前 {max} 条。',
  'label.export.confirm': '导出',
  'label.export.running': '正在导出',
  'label.export.progress': '已拉取 {fetched} / {total} 条',
  'label.export.progress-unknown': '已拉取 {fetched} 条',
  'label.export.done': '已导出 {count} 条',
  'label.export.done-one': '已导出 1 条',
  'label.export.done-capped': '文件只含前 {max} 条（共 {total} 条匹配）。',
  'label.export.done-capped-unknown': '文件只含前 {max} 条，匹配的还有更多。',
  'label.export.retry': '重试',
  'label.export.groups': '{count} 组',
  'label.export.groups-one': '1 组',
  'label.export.groups-first': '前 {count} 组（后面还有组，不在文件里）',
  'label.export.groups-first-one': '第 1 组（后面还有组，不在文件里）',
  'label.export.whole': '1 行：范围内的全部记录',
  'label.export.and-totals': '{rows}，末行是合计',
  'label.export.done-analysis': '已导出：{rows}',
  'export.failed': '导出失败：{reason}',
} satisfies Record<keyof typeof exportMessages, string>;
