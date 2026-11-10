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

import type { chartIssueMessages } from '../chartIssues.js';

/**
 * `chartIssueMessages` 的简体中文，逐键对应（`../chartIssues.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const chartIssuesZhCN = {
  'chart.fit.needs-dimension': '要一个维度',
  'chart.fit.needs-one-dimension': '只能有一个维度',
  'chart.fit.needs-two-dimensions': '要两个维度',
  'chart.fit.too-many-dimensions': '最多两个维度',
  'chart.fit.needs-two-metrics': '要两个指标',
  'chart.fit.needs-no-dimension': '有维度时不适用',
  'chart.fit.needs-quantity': '要数量指标，时间画不成图',
  'chart.fit.needs-category': '阶段要一个类别维度',
  'chart.fit.needs-two-stages': '要至少两组作阶段',
  'chart.fit.not-steps': '这个维度的值是并列的类目，不是先后的步骤',
  'chart.fit.needs-additive': '要记录数或总和',
  'chart.fit.needs-share': '占比只对可加的指标成立',
  'chart.fit.needs-five-numbers': '要同一字段的最小值、三个百分位与最大值',
  'chart.fit.needs-date': '要一个日期维度',
  'chart.fit.needs-ohlc': '要同一字段的期初值、最大值、最小值与期末值',
  'chart.fit.needs-three-metrics': '要至少三个指标',
  'chart.fit.too-many-levels': '最多四个维度',
  'chart.fit.needs-day': '要一个按日的日期维度',
  'chart.fit.needs-date-and-split': '要一个日期维度与另一个维度',
  'chart.fit.needs-region': '要一个按取值分组的地区维度',
  'chart.fit.needs-map': '这里没有可用的地图',
  'chart.as-table': '选定的图型画不了这个结果，先以表格显示。',
  'chart.cartesian.percent-not-additive':
    '百分比堆叠要可累加的指标（记录数或总和），「{metric}」不是。',
  'chart.combo.series-type-missing': '组合图的每个系列都要有自己的类型。',
  'chart.colors.invalid': '这不是图表画得出的颜色。',
  'chart.colors.malformed': '钉住的图表颜色要按系列或类别逐个列出。',
  'chart.family.missing': '{type} 图还没有 {family} 设置。',
  'chart.funnel.duplicate-stage': '漏斗的某个阶段列了两次。',
  'chart.funnel.metrics-need-no-group': '按指标分阶段的漏斗不能再有维度。',
  'chart.funnel.too-few-stages': '漏斗至少要有两个阶段。',
  'chart.funnel.not-additive':
    '漏斗要一个可累加的指标（记录数或总和），「{metric}」不是。',
  'chart.funnel.stages-need-category':
    '漏斗的阶段取自类别维度，日期与数值区间不行。',
  'chart.group.unconsumed': '图表没有用上维度「{alias}」。',
  'chart.group.unknown': '图表用到了一个这个分析没有的维度。',
  'chart.heatmap.same-axes': '热力图需要两个不同的轴。',
  'chart.treemap.not-additive':
    '矩形树图的每一块是整体的一部分，要一个可累加的指标（记录数或总和），「{metric}」不是。',
  'chart.treemap.same-levels': '矩形树图的两层要是两个不同的维度。',
  'chart.boxplot.not-five-numbers':
    '箱线图要同一字段、同一条件下的最小值、从低到高三个百分位与最大值。',
  'chart.candlestick.needs-date': 'K 线图沿日期维度排。',
  'chart.candlestick.not-ohlc':
    'K 线图要同一字段、同一条件下的期初值、最大值、最小值与期末值。',
  'chart.gauge.needs-no-group': '刻度盘不能有维度。',
  'chart.gauge.not-a-number': '这里要一个数。',
  'chart.gauge.empty-scale': '刻度终点要大于起点。',
  'chart.radar.too-few-metrics': '雷达图要至少三个指标。',
  'chart.radar.duplicate-metric': '雷达图把同一个指标列了两次。',
  'chart.sunburst.too-few-levels': '旭日图要至少两个维度。',
  'chart.sunburst.too-many-levels': '旭日图最多画四个维度。',
  'chart.sunburst.same-levels': '旭日图把同一个维度列了两次。',
  'chart.sunburst.not-additive':
    '旭日图的各部分加起来是上一层，所以要可加的指标（记录数或总和），不能是 {metric}。',
  'chart.tree.too-few-levels': '树图要至少两个维度。',
  'chart.tree.too-many-levels': '树图最多画四个维度。',
  'chart.tree.same-levels': '树图把同一个维度列了两次。',
  'chart.tree.not-additive':
    '树图的各部分加起来是上一层，所以要可加的指标（记录数或总和），不能是 {metric}。',
  'chart.sankey.too-few-levels': '桑基图要至少两个维度。',
  'chart.sankey.too-many-levels': '桑基图最多画四个维度。',
  'chart.sankey.same-levels': '桑基图把同一个维度列了两次。',
  'chart.sankey.not-additive':
    '桑基图的流向加起来是流过的总量，所以要可加的指标（记录数或总和），不能是 {metric}。',
  'chart.map.needs-region': '地图的地区要是一个维度的取值，不能是区间或日期。',
  'chart.map.name-invalid': '地图要有名字。',
  'chart.calendar.needs-day': '日历热力图按天排：维度要是按日的日期。',
  'chart.themeRiver.needs-date': '河流图沿日期维度流。',
  'chart.themeRiver.same-axes': '河流图要两个不同的维度。',
  'chart.themeRiver.not-additive':
    '河流图把各条河流叠起来，所以要可加的指标（记录数或总和），不能是 {metric}。',
  'chart.parallel.too-few-metrics': '平行坐标图要至少三个指标。',
  'chart.parallel.duplicate-metric': '平行坐标图把同一个指标列了两次。',
  'chart.waterfall.not-additive':
    '瀑布图把每一步加起来，要一个可累加的指标（记录数或总和），「{metric}」不是。',
  'chart.metric.needs-no-group': '指标卡不能有维度。',
  'chart.metric.trend-alias-mismatch': '趋势必须用维度「{alias}」。',
  'chart.metric.trend-needs-one-date-group': '趋势正好需要一个时间维度。',
  'chart.metric.trend-not-additive':
    '趋势主数需要可累加的指标，或由可累加指标算出的指标（如客单价），「{metric}」不是。',
  'chart.metric.unknown': '图表用到了一个这个分析没有的指标。',
  'chart.metric.moment': '「{alias}」是时间点，图表不画它，也不拿它作比较。',
  'chart.pie.not-additive':
    '饼图的扇区是整体的占比，要一个可累加的指标（记录数或总和），「{metric}」不是。',
  'chart.axis.scale-unknown': '没有这种坐标轴刻度。',
  'chart.pie.maxSlices-too-small': '至少留两个扇区。',
  'chart.referenceLine.empty-axis': '参考线所在的轴上要有系列。',
  'chart.referenceLine.value-missing': '参考线要站在一个数值或一个统计量上。',
  'chart.referenceLine.statistic-unknown': '没有这种统计量。',
  'chart.referenceLine.metric-not-drawn':
    '参考线取的是「{metric}」，这根轴上没有画它。',
  'chart.referenceBand.order': '目标区间要从小的数到大的数。',
  'chart.derived.kind-unknown': '没有这种算出的线。',
  'chart.derived.metric-not-drawn':
    '算出的线跟的是「{metric}」，图上没有画它。',
  'chart.derived.window': '移动平均的期数要在 2 到 {max} 之间。',
  'chart.scatter.same-metrics': '散点图需要两个不同的指标。',
  'chart.splitBy.needs-one-series': '拆分图只显示一个指标。',
  'chart.splitBy.same-as-x': '拆分不能重复横轴。',
  'chart.type.unknown': '这个图表类型不可用。',
} satisfies Record<keyof typeof chartIssueMessages, string>;
