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

import type { definitionMessages } from '../definition.js';

/**
 * `definitionMessages` 的简体中文，逐键对应（`../definition.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const definitionZhCN = {
  // 定义准入。
  'definition.field.search-fields-unknown':
    '{field} 搜索 {missing}，定义没有声明它。',
  'definition.search.reference-label':
    '{field} 搜索 {references}，它们存的是 id、显示为名称：服务端匹配存储的 id，不匹配显示的名称。把名称冗余进读模型（例如 customerName），搜索那个字段。',
  'definition.field.element-search-fields-required':
    '{field} 在条目里搜索，要写明它搜条目的哪些字段。',
  'definition.field.search-mode-invalid':
    '{field} 声明了未知的搜索模式：{value}。',
  'definition.analysis.date-part-not-temporal':
    '{field} 提供了按星期、时段分组，但它存的不是日期或时间。',
  'definition.analysis.date-part-unknown':
    '{field} 声明了未知的周期：{value}。',
  'definition.analysis.default-limit-too-large': '默认组数上限超过了最大值。',
  'definition.analysis.element-field-unknown':
    '{path} 没有声明名为 {field} 的字段。',
  'definition.analysis.element-undeclared':
    '分析展开了 {path}，它不是存放条目的字段。',
  'definition.analysis.elements-too-many':
    '分析声明的嵌套层数超过了 {max} 层。',
  'definition.analysis.field-unknown': '分析能力写了 {field}，定义没有声明它。',
  'definition.analysis.limit-invalid': '分析上限必须是正整数。',
  'definition.analysis.no-metric': '分析能力没有给出可以起步的指标。',
  'definition.field.cell-invalid': '{field} 声明了未知的单元格读法：{value}。',
  'definition.field.element-title-unknown':
    '{field} 以 {title} 作为元素的标题，但它的元素没有声明这个字段。',
  'definition.field.element-title-not-a-value':
    '{field} 以 {title} 作为元素的标题，但它不持有可读的值。',
  'definition.field.editor-removed':
    '{field} 声明了 editor，这个成员已经没有了，删掉即可。',
  'definition.field.temporal-invalid':
    '{field} 声明的时间存储方式引擎写不出来：{value}。',
  'definition.field.numeric-invalid':
    '{field} 声明的数值格式引擎写不出来：{value}。',
  'definition.field.temporal-misplaced':
    '{field} 声明了时间存储方式，但它的类型 {kind} 不写时间。',
  'definition.field.time-precision-invalid':
    '{field} 声明了未知的时间精度：{value}。',
  'definition.field.time-precision-misplaced':
    '{field} 声明了时间精度，但它读作 {cell}，没有时刻。',
  'definition.field.tone-invalid': '{field} 声明了未知的选项语气：{value}。',
  'definition.field.boolean-option':
    '{field} 是是非字段，选项只能写 "true" 与 "false"，不能是 {value}。',
  'definition.field.duplicate': '字段 {field} 声明了两次。',
  'definition.fieldGroup.duplicate': '分组 {group} 声明了两次。',
  'definition.fieldGroup.field-duplicate':
    '分组 {group} 列了 {field}，它已经列在另一个分组下。',
  'definition.fieldGroup.field-unknown':
    '分组 {group} 列了 {field}，定义没有声明它。',
  'definition.fieldGroup.invalid': '字段分组需要 id 和标签。',
  'definition.field.string-comparison-invalid':
    '{field} 声明了未知的文本比较方式：{value}。',
  'definition.field.kind-unregistered': '{field} 用了未注册的类型 {kind}。',
  'definition.field.name-invalid': '{field} 不是可用的字段名。',
  'definition.id.separator': '定义 id 不能带 {separator}。',
  'definition.record.layouts-empty': '记录能力没有给出布局。',
  'definition.record.kanban-no-lane': '看板视图要一个带选项的枚举字段来分栏。',
  'definition.record.max-window-invalid':
    '分页窗口必须是大于零的整数行数，而不是 {value}。',
  'definition.record.max-sort-fields-invalid':
    '排序字段上限必须是整数个字段，而不是 {value}。',
  'definition.record.max-window-cursor':
    '分页窗口限制的是页码，而这个源按游标翻页。',
  'definition.record.row-key-unknown': '行键 {field} 不是已声明的字段。',
  'definition.record.row-field-unknown': '行字段 {field} 不是已声明的字段。',
  'definition.record.row-field-not-a-path':
    '行字段 {field} 是搜索或元数据句柄，不是一行里的内容。',
  'definition.record.row-name-unknown': '行名 {field} 不是已声明的字段。',
  'definition.record.row-name-not-a-path':
    '行名 {field} 是搜索或元数据句柄，不是一行里的内容。',
  'definition.record.row-key-unsortable':
    '行键 {field} 必须可排序：每一页最后都按它排，同一行才不会出现在两页上。',
  'definition.view.id-duplicate': '两个视图共用了 id {id}。',
  'definition.view.id-separator': '视图 id 不能带 {separator}。',
  'definition.view.kind-mismatch': '{kind} 视图不属于 {definition} 定义。',
  'definition.view.analysis-open':
    '这张视图要的分析由数据源给：快照里没有，只在给出分析能力的数据源上打得开。',
  'definition.view.owned-invalid': '面板 {panel} 自带的分析不完整或设置有误。',
  'definition.field.undescribed': '数据源的描述里没有 {field} 这个路径。',
  'definition.field.kind-unknown':
    '描述没有说 {field} 存的是哪种值，请给它写 kind。',
  'definition.field.unlabelled': '{field} 没有写名字，只能按描述或路径显示。',
  'definition.field.deprecated':
    '{field} 已被数据源弃用（{message}），保留它请写明理由。',
  'definition.field.not-elements': '{field} 不是数据源列出条目的数组。',
  'definition.field.sort-wider': '{field} 在数据源上不能排序。',
  'definition.field.operator-wider': '{field} 在数据源上不收「{operator}」。',
  'definition.field.summary-wider': '{field} 在数据源上不能按 {fn} 汇总。',
  'definition.field.analysis-wider':
    '{field} 在数据源上不能用于分析的 {what}。',
  'definition.option.undescribed': '{value} 不是数据源为 {field} 列出的值。',
  'definition.analysis.wider': '数据源的分析不提供 {what}。',
  'definition.record.paging-wider': '数据源不按 {paging} 分页。',
  'definition.timeField.unknown': '时间字段 {field} 不是这个定义的字段。',
  'definition.timeField.not-time': '时间字段 {field} 存的不是时刻。',
  'definition.text.unknown': '键 {key} 没有给出措辞。',
  'definition.text.fallback':
    '当下的措辞没有给出键 {key}，改用引擎起始的措辞说出。',
  'definition.source.unregistered':
    '没有为 {source} 登记数据源：注册这份定义时带上它的数据源。',
  'definition.descriptor.missing':
    '没有给数据源 {source} 的描述，它的能力没有核对。',
  'binding.definition.unknown':
    '绑定指向 {id}，但没有哪份资源登记了它：它绑定的都不会生效。',
  'binding.action.primary-extra':
    '{id} 的绑定把多个操作标为主操作：{first} 画成主按钮，{others} 画成普通操作。',
} satisfies Record<keyof typeof definitionMessages, string>;
