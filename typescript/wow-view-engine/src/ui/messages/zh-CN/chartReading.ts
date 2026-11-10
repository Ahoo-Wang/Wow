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

import { AggregationDateUnits } from '../../../model/index.js';
import { wireKey } from '../wireKey.js';
import type { chartReadingMessages } from '../chartReading.js';

/**
 * `chartReadingMessages` 的简体中文，逐键对应（`../chartReading.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const chartReadingZhCN = {
  'label.chart.gap.note': '{what}没有画：{reason}',
  'label.chart.gap.split': '拆分后每个值一条线，算出的线只画在不拆分的图上。',
  'label.chart.gap.not-time': '横轴不是时间，这些线只沿时间算。',
  'label.chart.gap.not-sorted':
    '横轴是类别时，累计只按它这个指标的顺序才有意义：先按这个指标排序。',
  'label.chart.gap.narrowed': '「只保留」筛掉了一些组，算出的线会不完整。',
  'label.chart.gap.cut-short': '结果只显示了前 {limit} 组，算出的线会不完整。',
  'label.chart.gap.holes': '时间轴上有不确知的空缺，算出的线会不准。',
  'label.chart.gap.not-additive': '累计只对可累加的指标成立（记录数或总和）。',
  'label.chart.gap.too-few': '点太少，算不出。',
  'label.chart.gap.unsupported':
    '数据源算不了上一期（或按周分组时的去年同期）。',
  'label.chart.gap.shares': '图画的是百分比堆叠，占比算出的线没有意义。',
  'label.chart.gap.statistic-split':
    '拆分后每个值一条线，平均线与中位线只画在不拆分的图上。',
  'label.chart.gap.none-measured': '没有量到的值可算。',
  'label.chart.log-refused':
    '{axis}按线性刻度画：轴上有 0 或负数，对数刻度放不下。',
  'label.chart.crowded': '超过 {slots} 条，颜色会重复，建议改用热力图或表格。',
  'label.chart.other': '其他',
  'label.chart.series.of-field': '{field}：{value}',
  'label.chart.share-basis': '占比按显示的组计算',
  [wireKey('label.chart.filled', AggregationDateUnits.YEAR)]:
    '{value}（这一年没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.QUARTER)]:
    '{value}（这个季度没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.MONTH)]:
    '{value}（这个月没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.WEEK)]:
    '{value}（这一周没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.DAY)]:
    '{value}（这一天没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.HOUR)]:
    '{value}（这个小时没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.MINUTE)]:
    '{value}（这一分钟没有记录）',
  [wireKey('label.chart.filled', AggregationDateUnits.SECOND)]:
    '{value}（这一秒没有记录）',
  'label.chart.filled.group': '{value}（这一组没有记录）',
  'label.chart.total': '合计',
  'label.chart.trend': '趋势',
  'label.chart.figure': '{type}：{category} · {measures}',
  'label.chart.figure.plain': '{type}：{measures}',
  'label.chart.sparkline': '{name}，随时间变化',
  'label.chart.reading': '{name}，数据表',
  'label.chart.sentence':
    '共 {count} 组，最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence-one':
    '共 1 组，最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence.time':
    '共 {count} 期，从 {first} 到 {last}，{trend}；最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence.up': '总体上升',
  'label.chart.sentence.down': '总体下降',
  'label.chart.sentence.flat': '总体持平',
  'label.chart.ongoing': '{period}（进行中）',
  'label.chart.ongoing.band': '进行中',
  'label.chart.sentence.unfinished': '{period}还没结束，未计入。',
  'label.chart.sentence.trend-of': '{measure} {trend}',
  'label.chart.sentence.other-axis':
    '另一根轴上的 {measures}：最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence.scatter':
    '共 {count} 个点，{x} 从 {xLow} 到 {xHigh}，{y} 从 {yLow} 到 {yHigh}。',
  'label.chart.sentence.scatter-one':
    '共 1 个点，{x} 从 {xLow} 到 {xHigh}，{y} 从 {yLow} 到 {yHigh}。',
  'label.chart.sentence.boxplot':
    '共 {count} 组，中位数最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence.boxplot-one':
    '共 1 组，中位数最高 {high} {highValue}，最低 {low} {lowValue}。',
  'label.chart.sentence.gauge': '{value}，刻度从 {min} 到 {max}。',
  'label.chart.sentence.funnel':
    '共 {count} 段，从 {first} {firstValue} 到 {last} {lastValue}，总转化 {overall}。',
  'label.chart.sentence.funnel-drop':
    '流失最多在 {from} → {to}：{drop}（{rate}）。',
  'label.chart.sentence.candlestick':
    '共 {count} 期，{first} 至 {last}：开 {open}，收 {close}；最高 {high}，最低 {low}。',
  'label.chart.sentence.gauge-target':
    '{value}，达成目标 {target} 的 {share}。',
  'label.chart.sentence.profile-metric':
    '{metric}：最高 {high} {highValue}，最低 {low} {lowValue}',
  'label.chart.sentence.profile-join': '；',
  'label.chart.sentence.profile-readings': '{readings}。',
  'label.chart.sentence.profiles': '共 {count} 组，{metrics} 个指标。',
  'label.chart.sentence.sankey': '共 {count} 条流向，最大 {high} {highValue}。',
  'label.chart.sentence.sankey-one': '共 1 条流向，最大 {high} {highValue}。',
  'label.chart.sentence.themeRiver':
    '{streams} 条河流，共 {count} 期，从 {first} 到 {last}，{trend}。',
  'label.chart.column.category': '类别',
  'label.chart.column.value': '数值',
  'label.chart.column.direction': '涨跌',
  'label.chart.column.x': 'X 轴',
  'label.chart.column.y': 'Y 轴',
  'label.chart.column.stage': '阶段',
  'label.chart.column.conversion.previous': '转化率（相对上一段）',
  'label.chart.column.conversion.first': '转化率（相对第一段）',
  'label.chart.column.drop': '较上一段流失',
  'label.chart.column.cumulative': '累计：至少到达这一段',
  'label.chart.column.compare': '对比值',
  'label.chart.column.target': '目标',
  'label.chart.column.change': '变化',
  'label.chart.column.running': '累计',
  'label.chart.column.share': '占比',
  'label.chart.column.from': '从',
  'label.chart.column.to': '到',
  'label.chart.column.scale-min': '刻度起点',
  'label.chart.column.scale-max': '刻度终点',
  'label.chart.column.reached': '达成率',
  'label.chart.period.week': '{start}起的一周',
  'label.chart.period.so-far': '{period}（至今）',
  'label.chart.period.whole': '范围内全部',
  'label.chart.period.skipped': '{period}还没结束，未计入',
  'label.chart.change.against': '较上一期',
  [wireKey('label.chart.change.against', AggregationDateUnits.YEAR)]:
    '较上一年',
  [wireKey('label.chart.change.against', AggregationDateUnits.QUARTER)]:
    '较上季度',
  [wireKey('label.chart.change.against', AggregationDateUnits.MONTH)]: '较上月',
  [wireKey('label.chart.change.against', AggregationDateUnits.WEEK)]: '较上周',
  [wireKey('label.chart.change.against', AggregationDateUnits.DAY)]: '较前一日',
  [wireKey('label.chart.change.against', AggregationDateUnits.HOUR)]:
    '较前一小时',
  [wireKey('label.chart.change.against', AggregationDateUnits.MINUTE)]:
    '较前一分钟',
  [wireKey('label.chart.change.against', AggregationDateUnits.SECOND)]:
    '较前一秒',
  'label.chart.change.against.previous-year': '较去年同期',
  'label.chart.change.points': '{amount} 个百分点',
  'label.chart.change.none': '没有上一期可比',
  'label.chart.change.none.previous-year': '没有去年同期可比',
  'label.chart.change.unknown.previous-year': '去年同期没有可比的数',
  'label.chart.change.unknown': '上一期没有可比的数',
  'label.chart.change.unmatched':
    '所选日期在本期与上一期覆盖的长短不同，不作比较',
  'label.chart.period.range.in-year': '{year}{start}–{end}',
  'label.chart.period.range': '{from}–{to}',
  'label.chart.period.cut':
    '超过 {limit} 期，最近一期不在结果里，没有可显示的数。请缩小日期范围或按更粗的粒度看。',
} satisfies Record<keyof typeof chartReadingMessages, string>;
