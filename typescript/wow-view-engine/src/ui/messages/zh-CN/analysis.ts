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

import {
  AggregationDateParts,
  AggregationDateUnits,
  AggregationGroupTypes,
  ComparisonOperators,
  DateDiffUnits,
  SortDirections,
} from '../../../model/index.js';
import { wireKey } from '../wireKey.js';
import type { analysisMessages } from '../analysis.js';

/**
 * `analysisMessages` 的简体中文，逐键对应（`../analysis.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const analysisZhCN = {
  // 分析编辑器与图表，以及它们背后的两个内核。
  'label.analysis.row-count': '记录数',
  'label.analysis.caption': '正在显示 {count} 组，耗时 {seconds} 秒',
  'label.analysis.caption-whole': '耗时 {seconds} 秒',
  'label.analysis.caption-one': '正在显示 1 组，耗时 {seconds} 秒',
  'label.analysis.answered': '结果已更新',
  'label.analysis.editor': '分析',
  'label.analysis.slot.range': '范围',
  'label.analysis.slot.dimensions': '维度',
  'label.analysis.slot.metrics': '指标',
  'label.analysis.slot.result': '结果',
  'label.analysis.slot.elements': '展开',
  'label.analysis.tip-of': '{term}说明',
  'label.analysis.tip.elements':
    '把记录里的数组拆成元素来数：展开 {name} 后，每个 {name} 算一条，指标和维度从 {name} 的字段里选；可以再往里一层。',
  'label.analysis.tip.metrics':
    '要算的数：记录数，或某个字段的总和、平均等，每个都可以只算满足条件的记录；可拖动调整顺序，派生指标只能排在它引用的指标之后。',
  'label.analysis.tip.dimensions':
    '按什么分组来比：每个维度把结果切成组，几个维度依次再切。',
  'label.analysis.tip.result':
    '留哪些组、怎么排：只保留满足条件的组，按维度或指标排序，只取前 N 组。',
  'label.analysis.tip.range':
    '只算哪些记录；筛的是最外层的记录，不受展开影响。',
  'label.analysis.add': '添加',
  'label.analysis.dropped.expand': '展开 {name} 后去掉了 {what}',
  'label.analysis.dropped.collapse': '收起 {name} 后去掉了 {what}',
  'label.analysis.dropped.edit': '这次修改一并去掉了 {what}',
  'label.analysis.dropped.metric': '1 个指标：{names}',
  'label.analysis.dropped.metrics': '{count} 个指标：{names}',
  'label.analysis.dropped.dimension': '1 个维度：{names}',
  'label.analysis.dropped.dimensions': '{count} 个维度：{names}',
  'label.analysis.dropped.both': '{first}；{second}',
  'label.analysis.dropped.undo': '撤销',
  'label.analysis.dropped.dismiss': '关闭提示',
  'label.analysis.dropped.undone': '已撤销，问题恢复原样',
  'label.analysis.move-stop.after':
    '「{name}」按「{other}」计算，只能排在它后面。',
  'label.analysis.move-stop.before':
    '「{other}」按「{name}」计算，「{name}」只能排在它前面。',
  'label.analysis.remove-last': '至少要留一个指标。',
  'label.analysis.remove-cascade': '其余指标都按它计算，去掉它就没有指标了。',
  'label.analysis.expand-into': '展开 {name}',
  'label.analysis.collapse': '收起 {name}',
  'label.analysis.unit': '计数单位：{name}',
  'label.analysis.records': '记录',
  'label.analysis.element-condition-of': '{name} 只算满足条件的明细项',
  'label.analysis.element-condition-title': '只算满足条件的明细项',
  'label.analysis.element-condition': '只算满足条件的明细项',
  'label.analysis.conditions-mode': '条件：{mode}',
  'label.analysis.granularity': '粒度',
  [wireKey('label.date-unit', AggregationDateUnits.YEAR)]: '按年',
  [wireKey('label.date-unit', AggregationDateUnits.QUARTER)]: '按季',
  [wireKey('label.date-unit', AggregationDateUnits.MONTH)]: '按月',
  [wireKey('label.date-unit', AggregationDateUnits.WEEK)]: '按周',
  [wireKey('label.date-unit', AggregationDateUnits.DAY)]: '按日',
  [wireKey('label.date-unit', AggregationDateUnits.HOUR)]: '按小时',
  [wireKey('label.date-unit', AggregationDateUnits.MINUTE)]: '按分钟',
  [wireKey('label.date-unit', AggregationDateUnits.SECOND)]: '按秒',
  'label.analysis.date-part': '周期',
  [wireKey('label.date-part', AggregationDateParts.DAY_OF_WEEK)]: '按星期',
  [wireKey('label.date-part', AggregationDateParts.HOUR_OF_DAY)]:
    '按时段（几点）',
  [wireKey('label.date-part', AggregationDateParts.DAY_OF_MONTH)]: '按每月几号',
  [wireKey('label.date-part', AggregationDateParts.MONTH_OF_YEAR)]: '按月份',
  [wireKey('label.analysis.part', AggregationDateParts.DAY_OF_WEEK)]:
    '{field}（星期）',
  [wireKey('label.analysis.part', AggregationDateParts.HOUR_OF_DAY)]:
    '{field}（时段）',
  [wireKey('label.analysis.part', AggregationDateParts.DAY_OF_MONTH)]:
    '{field}（几号）',
  [wireKey('label.analysis.part', AggregationDateParts.MONTH_OF_YEAR)]:
    '{field}（月份）',
  [wireKey('label.analysis.dated', AggregationDateUnits.YEAR)]:
    '{field}（按年）',
  [wireKey('label.analysis.dated', AggregationDateUnits.QUARTER)]:
    '{field}（按季）',
  [wireKey('label.analysis.dated', AggregationDateUnits.MONTH)]:
    '{field}（按月）',
  [wireKey('label.analysis.dated', AggregationDateUnits.WEEK)]:
    '{field}（按周）',
  [wireKey('label.analysis.dated', AggregationDateUnits.DAY)]:
    '{field}（按日）',
  [wireKey('label.analysis.dated', AggregationDateUnits.HOUR)]:
    '{field}（按小时）',
  [wireKey('label.analysis.dated', AggregationDateUnits.MINUTE)]:
    '{field}（按分钟）',
  [wireKey('label.analysis.dated', AggregationDateUnits.SECOND)]:
    '{field}（按秒）',
  'label.analysis.band': '{from}～{to}',
  'label.analysis.interval': '区间宽度',
  'label.analysis.percentile': '百分位',
  'label.analysis.open-editor': '打开分析',
  'label.analysis.open-chart-options': '打开图表选项',
  'label.analysis.reading': '{dimensions} · {metrics}',
  'label.analysis.reading-flat': '{metrics}',
  'label.analysis.reading-kept': '{reading} · 只保留 {conditions}',
  'label.analysis.reading-kept-row': '{metric} {operator} {value}',
  'label.analysis.reading-kept-custom': '{reading} · 只保留符合自定义规则的组',
  'label.analysis.layout': '结果呈现为',
  'label.analysis.add-group': '添加维度',
  'label.analysis.group-by-currency': '按「{field}」分组',
  'label.analysis.add-metric': '添加指标',
  'label.analysis.add-formula': '公式',
  'label.analysis.add-derived': '派生指标',
  'label.analysis.add-duration': '两个时刻之差',
  'label.analysis.duration-from': '{name} 从哪个时刻',
  'label.analysis.duration-to': '{name} 到哪个时刻',
  'label.analysis.duration-unit': '{name} 的单位',
  [wireKey('label.date-diff-unit', DateDiffUnits.SECOND)]: '秒',
  [wireKey('label.date-diff-unit', DateDiffUnits.MINUTE)]: '分钟',
  [wireKey('label.date-diff-unit', DateDiffUnits.HOUR)]: '小时',
  [wireKey('label.date-diff-unit', DateDiffUnits.DAY)]: '天',
  'label.analysis.group-by-duration': '按这个时长分区间',
  'label.analysis.derived.style-of': '{name}读作',
  'label.analysis.derived.style.number': '数字',
  'label.analysis.derived.style.percent': '百分比',
  'label.analysis.derived.style.currency': '金额',
  'label.analysis.derived.decimals': '{name}的小数位',
  'label.analysis.derived.currency': '{name}的币种',
  'label.analysis.derived.currency-inherited': '随参与计算的指标',
  'label.analysis.operand-left': '{name} 的左边',
  'label.analysis.operand-right': '{name} 的右边',
  'label.analysis.operand-number': '数字',
  'label.analysis.operand-value': '{name} 里的数字',
  'label.analysis.operator': '{name} 的运算',
  'label.analysis.expression-unreadable':
    '这个指标用这里编辑不了的表达式写成。',
  'label.analysis.having': '添加条件',
  'label.analysis.having-first': '只保留…',
  'label.analysis.having-title': '只保留',
  'label.analysis.having-row': '只保留条件 {index}',
  'label.analysis.having-and': '并且',
  'label.analysis.having-metric': '按哪个指标',
  'label.analysis.having-operator': '比较',
  'label.analysis.having-value': '比较的数值',
  'label.analysis.remove-having': '移除这条',
  'label.analysis.having-note': '没有值的组不会保留。',
  'label.analysis.having-unreadable':
    '这个视图用这里编辑不了的规则只保留了部分组。',
  'label.analysis.clear-having': '清空',
  'label.analysis.top-first': '每组只留前几个…',
  'label.analysis.top-title': '每组只留前几个',
  'label.analysis.top-each': '每个',
  'label.analysis.top-within': '在哪个维度里排',
  'label.analysis.top-keep': '只留前',
  'label.analysis.top-count': '留几个',
  'label.analysis.top-by': '个，按',
  'label.analysis.top-metric': '按哪个指标排',
  'label.analysis.top-direction': '排的次序',
  [wireKey('label.analysis.top-direction', SortDirections.DESC)]: '从高到低',
  [wireKey('label.analysis.top-direction', SortDirections.ASC)]: '从低到高',
  'label.analysis.top-invalid': '须为 1～{max} 的整数。',
  'label.analysis.remove-top': '每组不再只留前几个',
  'label.analysis.top-note': '并列的组名次相同，都会留下。',
  'label.analysis.reading-top':
    '{reading} · 每个{within}只留{metric}{order}前 {count} 个',
  [wireKey('label.having.op', ComparisonOperators.GT)]: '大于',
  [wireKey('label.having.op', ComparisonOperators.GTE)]: '不小于',
  [wireKey('label.having.op', ComparisonOperators.LT)]: '小于',
  [wireKey('label.having.op', ComparisonOperators.LTE)]: '不大于',
  [wireKey('label.having.op', ComparisonOperators.EQ)]: '等于',
  [wireKey('label.having.op', ComparisonOperators.NE)]: '不等于',
  'label.analysis.totals': '合计行',
  'label.analysis.totals-whole':
    '没有维度时结果只有一行，就是范围内全部记录，不另画合计行。',
  'label.analysis.row-limit': '前 N 组',
  'label.analysis.row-limit-invalid': '须为 1～{max} 的整数',
  'label.analysis.row-limit-unsorted': '未排序时由数据源决定取哪几组',
  'label.analysis.totals-scope': '范围内全部记录',
  'label.analysis.totals-hidden.cut': '含前 {limit} 组之外的组',
  'label.analysis.totals-hidden.maybe-cut': '可能含前 {limit} 组之外的组',
  'label.analysis.totals-hidden.kept': '含被「只保留」去掉的组',
  'label.analysis.approximate': '近似值',
  'label.analysis.any-note': '任一值：每次查询返回的不保证是同一个。',
  'label.analysis.first-last-note':
    '期初值、期末值是这一组里时间最早、最晚的那条记录的值。',
  'label.analysis.order-by-of': '{name} 按什么先后',
  'label.analysis.order-by-default': '按事件时间',
  'label.analysis.order-by': '按{field}',
  'label.analysis.visualize': '可视化',
  'label.analysis.auto-run': '自动运行',
  'label.analysis.auto-run-hint':
    '维度、指标与结果改了就跑；范围要点「应用」。',
  'label.analysis.auto-run-held':
    '范围里有未应用的条件（含未填完的），点「应用」后才会一起运行。',
  'label.analysis.grouping-of': '{name} 的维度设置',
  'label.analysis.card-menu': '{name} 的更多设置',
  'label.analysis.condition-of': '{name} 只算满足条件的记录',
  'label.analysis.condition-title': '只算满足条件的记录',
  'label.analysis.condition-close': '收起条件',
  'label.analysis.condition-remove': '去掉条件，算全部记录',
  'label.analysis.only-where': '只算 {conditions}',
  'label.analysis.metric-where': '{metric} · {value}',
  'label.analysis.metric-conditioned': '{metric} · 有条件',
  'label.analysis.name-it': '起个显示名',
  'label.analysis.copy-with-condition': '复制「{name}」并加条件',
  'label.analysis.five-numbers': '补齐箱线图的五个数',
  'label.analysis.ohlc': '补齐 K 线的四个数',
  'label.analysis.rename': '改显示名…',
  'label.analysis.display-name': '{name} 的显示名',
  'label.analysis.missing-bucket': '空值单独一组',
  'label.analysis.missing-group': '（空）',
  'label.analysis.dense': '补齐空的时段',
  'label.analysis.dense-alone': '补齐空的时段（只有一个维度时）',
  'label.analysis.dense-part': '没有记录的也列出来',
  'label.analysis.dense-part-alone': '没有记录的也列出来（只有一个维度时）',
  'label.analysis.remove-group': '移除维度 {name}',
  'label.analysis.function-of': '{name} 的汇总方式',
  'label.analysis.remove-metric': '移除指标 {name}',
  'label.analysis.empty': '没有符合条件的组',
  'label.analysis.empty-hint': '当前条件下没有记录可以分组。',
  'label.analysis.empty-view': '这个视图现在没有可以分组的记录。',
  'label.analysis.empty-none': '范围里没有记录可以分组',
  'label.analysis.empty-clear': '清空条件',
  'label.analysis.empty-restore': '回到保存的条件',
  'label.analysis.empty-edit': '调整范围',
  'label.analysis.as-table': '{type}画不了这个结果（{reason}），先以表格显示。',
  [wireKey('label.group.type', AggregationGroupTypes.TERMS)]: '原值',
  [wireKey('label.group.type', AggregationGroupTypes.HISTOGRAM)]: '数值区间',
  [wireKey('label.group.type', AggregationGroupTypes.DATE_HISTOGRAM)]:
    '时间粒度',
  [wireKey('label.group.type', AggregationGroupTypes.DATE_PART)]:
    '周期（星期、时段…）',
  'analysis.alias.duplicate': '显示名 {alias} 用了两次。',
  'analysis.alias.invalid': '{alias} 不是可用的显示名。',
  'analysis.alias.not-a-segment': '{alias} 不能带点号。',
  'analysis.alias.reserved': '{alias} 用了保留前缀。',
  'analysis.any.undeclared': '「{field}」不能作为样本值显示。',
  'analysis.capability.missing': '{definition} 不再提供分析视图。',
  'analysis.column.duplicate': '这个结果里「{alias}」出现了两次。',
  'analysis.column.unknown-alias': '这个结果里没有「{alias}」。',
  'analysis.config.malformed': '这个分析没有可用的结构。',
  'analysis.constant.not-finite': '常量必须是有限的数字。',
  'analysis.count.undeclared': '这份数据不提供记录数。',
  'analysis.derived.unknown-metric':
    '派生指标引用了「{metric}」，它没有在它之前声明。',
  'analysis.derived.moment-operand': '「{metric}」是时间点，不能参与计算。',
  'analysis.derived.format-invalid': '算出的指标读作数字、百分比或金额。',
  'analysis.derived.decimals': '小数位是 0 到 {max} 的整数。',
  'analysis.derived.currency-invalid': '这不是一个币种代码。',
  'analysis.derived.currency-unknown':
    '参与计算的指标不在同一个币种里：请指定它的币种。',
  'analysis.distinctCount.undeclared': '「{field}」不提供去重计数。',
  'analysis.element.out-of-chain': '「{path}」的展开属于展开链的另一层。',
  'analysis.element.undeclared': '「{path}」的展开不可用。',
  'analysis.elementFilter.empty': '这组条件是空的，所以每个明细项都会展开。',
  'analysis.elementFilter.incomplete':
    '给「{field}」一个值，否则每个明细项都会展开。',
  'analysis.elementFilter.search':
    '搜索不能决定展开哪些明细项：把「{field}」从这个条件里去掉。',
  'analysis.elements.too-many': '这份数据的展开太多了。',
  'analysis.expression.divide-by-zero': '这个表达式除以了零。',
  'analysis.expression.date-operand': '「{field}」是日期，不能参与计算。',
  'analysis.expression.malformed': '这个指标没有可用的表达式。',
  'analysis.expression.operand-unsupported': '数据源不能用「{field}」计算。',
  'analysis.metric.filter-field-unsupported':
    '数据源不能在指标自己的条件里用「{field}」。',
  'analysis.expression.too-deep': '表达式的嵌套超过了 {max} 层。',
  'analysis.expression.too-many-nodes': '表达式超过了 {max} 条。',
  'analysis.expressions.undeclared': '这份数据不提供计算表达式。',
  'analysis.field.outside-scope': '字段「{field}」不在展开后的范围里。',
  'analysis.field.unknown': '字段「{field}」在这里不可用。',
  'analysis.function.unsupported': '「{field}」不能求{fn}。',
  'analysis.group.blank-missing-key': '缺失值的占位文本是空的。',
  'analysis.group.blank-time-zone': '时区是空的。',
  'analysis.group.dense-unsupported': '数据源不能给时间维度补齐空档。',
  'analysis.group.dense-not-alone': '补齐空档的时间维度只能是唯一的维度。',
  'analysis.group.interval-not-positive': '数值区间的宽度必须大于零。',
  'analysis.group.missing-key-unsupported':
    '「{field}」不能为缺失值单独分一组，只有单值文本字段可以。',
  'analysis.group.unit-unsupported': '这个维度不能{unit}分组。',
  'analysis.first-last.undeclared': '「{field}」不能取期初值或期末值。',
  'analysis.first-last.order-by-required':
    '展开明细项后，期初值、期末值要指定按哪个时间先后取。',
  'analysis.date-diff.unit-unsupported': '这里两个时刻之差不能以{unit}计。',
  'analysis.date-diff.not-time': '「{field}」不是时刻，不能算两个时刻之差。',
  'analysis.group.part-unsupported': '这个维度不能{part}分组。',
  'analysis.label.blank': '显示名是空的。',
  'analysis.group.unsupported': '「{field}」不能以「{type}」方式分组。',
  'analysis.groups.too-many': '这份数据的维度太多了。',
  'analysis.having.malformed': '这个结果筛选没有可用的结构。',
  'analysis.having.requires-group': '筛选结果至少需要一个维度。',
  'analysis.having.too-deep': '结果筛选的嵌套超过了 {max} 层。',
  'analysis.having.too-many-nodes': '结果筛选超过了 {max} 条。',
  'analysis.having.undeclared': '这份数据不提供筛选结果。',
  'analysis.having.metric-unsupported': '数据源不能按「{metric}」保留分组。',
  'analysis.having.unknown-metric':
    '筛选引用了「{metric}」，它不是可用的指标。',
  'analysis.top.unsupported': '这份数据不能在每个维度值里只留前几个。',
  'analysis.top.requires-groups': '每个维度值里只留前几个，至少需要两个维度。',
  'analysis.top.unknown-within':
    '「{within}」不是这里的维度，不能在它里面只留前几个。',
  'analysis.top.unknown-metric': '「{metric}」不是能排名次的指标。',
  'analysis.top.count-out-of-range':
    '每个维度值里留下的个数须为 1～{max} 的整数。',
  'analysis.top.malformed': '这个「只留前几个」没有可用的结构。',
  'analysis.limit.out-of-range': '前 N 组须为 1～{max} 的整数。',
  'analysis.metric.type-unknown': '指标类型 {type} 不可用。',
  'analysis.metricFilter.empty': '这组条件是空的，所以指标覆盖全部记录。',
  'analysis.metricFilter.incomplete':
    '给「{field}」一个值，否则指标覆盖全部记录。',
  'analysis.metricFilter.not-scalar': '「{field}」没有单一值供指标条件判断。',
  'analysis.metrics.empty': '分析至少需要一个指标。',
  'analysis.metrics.too-many': '这份数据的指标太多了。',
  'analysis.percentile.out-of-range': '百分位要在 0 与 100 之间，不含两端。',
  'analysis.percentile.undeclared': '「{field}」不提供百分位。',
  'analysis.result.more-groups': '只显示了前 {limit} 组，还有更多未列出。',
  'analysis.metric.out-of-reach':
    '「{metric}」的时段不在所选日期内，这一列不适用，留空。',
  'analysis.result.at-limit': '只显示了前 {limit} 组，可能还有更多未列出。',
  'analysis.result.mixed-currency':
    '{metric}：有的组的记录分属几种货币，这些组的金额加不到一起，不显示；按「{field}」分组可以分开看。',
  'analysis.metric.currency-unchecked':
    '{metric} 按每条记录自己的币种记账，但数据源核对不了币种，加起来的数可能混了几种货币；按「{field}」分组可以分开看。',
  'analysis.split.whole-failed':
    '没能把较小的系列并成「其他」，颜色会重复：{reason}',
  'analysis.sort.duplicate': '排序已经按「{alias}」排过了。',
  'analysis.sort.root-group-first':
    '排序把「{alias}」排在了按记录本身字段的维度之前；这类维度须排在最前。',
  'analysis.sort.metric-unsupported':
    '数据源只按维度给分组排序，不能按「{alias}」。',
  'analysis.sort.requires-group': '排序至少需要一个维度。',
  'analysis.sort.too-many': '结果最多按 {max} 个维度或指标排序。',
  'analysis.sort.unknown-alias': '排序依据的「{alias}」不在这个结果里。',
} satisfies Record<keyof typeof analysisMessages, string>;
