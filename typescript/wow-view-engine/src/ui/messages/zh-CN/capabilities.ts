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

import type { capabilitiesMessages } from '../capabilities.js';

/**
 * `capabilitiesMessages` 的简体中文，逐键对应（`../capabilities.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const capabilitiesZhCN = {
  // 数据源的能力描述与定义对照的结果（capabilities.md）。与定义准入一样经
  // `onIssue` 报给研发，说的是部署没有兑现定义里的哪一项。
  'capability.field.unknown':
    '数据源没有列出 {field}：照常显示，但条件、排序与汇总都不再用它。',
  'capability.field.unfilterable': '数据源不接受 {field} 上的任何条件。',
  'capability.field.operators-narrowed':
    '数据源不接受 {field} 上的这些条件：{operators}。',
  'capability.field.unsortable': '数据源不能按 {field} 排序。',
  'capability.field.alias': '定义用别名 {field} 称呼 {path}：按 {path} 读取。',
  'capability.field.protected':
    '{field} 受保护：只显示，不能用来筛选、排序或检索。',
  'capability.field.deprecated': '数据源已弃用 {field}。',
  'capability.field.deprecated-because': '数据源已弃用 {field}：{reason}',
  'capability.field.not-projectable': '数据源不返回 {field}：这一列显示为空。',
  'capability.field.summary-narrowed': '数据源算不了 {field} 的 {summaries}。',
  'capability.field.temporal-mismatch':
    '{field} 声明为 {declared}，数据源却按 {described} 保存：它的日期条件会写错。',
  'capability.field.options-undescribed':
    '{field} 列出的 {values} 不在数据源声明的取值里。',
  'capability.search.unavailable': '数据源没有 {field} 能用的检索。',
  'capability.search.as-terms':
    '数据源不支持按短语检索，{field} 改为按词检索。',
  'capability.search.fields-narrowed':
    '在这个数据源上，{field} 检索不了 {fields}。',
  'capability.record.paging':
    '记录视图按 {paging} 翻页，数据源不提供这种翻页方式。',
  'capability.record.cursor-appended':
    '数据源给游标追加的是 {appended}，不是行键 {field}。',
  'capability.record.row-key-unsortable': '数据源不能按行键 {field} 排序。',
  'capability.analysis.count': '数据源不提供记录数。',
  'capability.analysis.expressions': '数据源不接受公式与派生指标。',
  'capability.analysis.having': '数据源不能按指标筛选分组。',
  'capability.analysis.metric-sort': '数据源只按维度给分组排序。',
  'capability.analysis.dense': '数据源不给时间维度补齐空档。',
  'capability.analysis.unavailable': '分析声明的聚合，数据源一项都做不了。',
  'capability.analysis.element-unavailable':
    '数据源不能在 {path} 的元素上聚合。',
  'capability.analysis.field-unavailable': '数据源不能聚合 {field}。',
  'capability.analysis.field-narrowed': '数据源不提供 {field} 上的 {dropped}。',
  'label.capability.missing-key': '缺失值分组',
  'label.capability.in-metric-filter': '按汇总值设条件',
  'label.capability.expression-input': '对计算值汇总',
  'label.capability.first-last': '期初值与期末值',
  'capability.descriptor.disagrees':
    '{source} 拒绝了一条它的能力描述 {version} 允许的查询（{code}）：服务与它的描述不一致。',
  'capability.descriptor.unavailable':
    '读不到 {source} 的能力描述，它的视图只按定义运行。',
} satisfies Record<keyof typeof capabilitiesMessages, string>;
