---
title: 'Issue code'
description: '视图引擎报告的每个 issue code 与它的缺省措辞，由公开面清单生成——@ahoo-wang/wow-view-engine'
---

# Issue code

引擎的每个内核都以同一种形状报告问题：`Issue`。措辞不在模型里：`code` 是稳定的分支键，`params` 带着句子需要的值，界面层按 `code` 在措辞目录里找句子。所以宿主可以按 `code` 分支（比如对某种失败给出自己的处理），也可以按键改写措辞（[措辞](./host#api-MessagesProvider)）。

相关指南：[写好一份定义](../../../guide/typescript/view-engine-definitions.md)（`admit` 报出的问题怎样读、怎样修）、[把视图引擎接进宿主](../../../guide/typescript/view-engine-host.md#messages)（按键改写措辞）。

## Issue {#api-Issue}

- `severity`：`error` 挡住应用与保存；`warning` 报告但不阻挡——有东西不对，或答案可能被误读；`note` 是读者应该知道的关于答案的事实，没有东西出错，比如视图自己的条数上限略过的分组。
- `path`：它在配置里的位置，比如 `['sort', 0, 'field']`。
- `params`：句子需要的值。数字是数量，按界面上其他数字的方式格式化；作为名字的数字（id、年份、行键）以字符串交出，原样保留。

`/ui` 的 `formatIssue(messages, issue, locale?)` 把一条 issue 说成句子，`formatMessage` 填好一个键的参数。

```ts
export interface Issue {
  code: string;
  params?: Record<string, string | number>;
  path: IssuePath;
  severity: IssueSeverity;
}
export type IssueSeverity = 'error' | 'warning' | 'note';
export type IssuePath = (string | number)[];
```

## code 是公开面

下面的表就是 `test/surface/issues.txt`——引擎能报的每个 code——连同简体中文目录给它的缺省措辞。改名或删掉一个 code，和删掉一个导出一样是破坏性变更，只出现在次版本里，并写进发布说明；新增一个 code 随时可以。

这份清单包括 `issue('…')` 直接报出的 code，也包括经辅助函数报出的：`defineView` 的准入发现（`definition.field.undescribed`、`definition.field.unlabelled`、`definition.field.*-wider` 等）、看板点击的警告、失败命令的基码（`view.open.failed` 等）。

**失败命令的 code 可能带后缀。** 存储或写入失败时，引擎在命令的基码后面接上发生了什么：存储的结局 `.conflict`、`.not_found`、`.forbidden`、`.invalid`、`.unavailable`、`.unavailable.server`、`.unsupported`，或写入的状态 `.conflict`、`.unknown`——比如 `view.open.failed.not_found`。表里只列目录为之单独措辞的组合；其余的沿着点号回落到基码的句子。所以按失败分支时用前缀：`issue.code.startsWith('view.open.failed')`。

措辞里的 `{field}`、`{max}` 等是 `params` 的占位符；`-one` 等复数形式不是 code，由数量挑选。

<!-- 生成区域：由 test/surface/issues.txt 与 zhCN 目录生成。
     重新生成：pnpm --filter @ahoo-wang/wow-view-engine reference:docs -->

<!-- issue-codes:begin -->

::: v-pre

### `analysis.*` {#issues-analysis}

| 代码 | 默认措辞 |
|---|---|
| `analysis.alias.duplicate` | 显示名 {alias} 用了两次。 |
| `analysis.alias.invalid` | {alias} 不是可用的显示名。 |
| `analysis.alias.not-a-segment` | {alias} 不能带点号。 |
| `analysis.alias.reserved` | {alias} 用了保留前缀。 |
| `analysis.any.undeclared` | 「{field}」不能作为样本值显示。 |
| `analysis.capability.missing` | {definition} 不再提供分析视图。 |
| `analysis.column.duplicate` | 这个结果里「{alias}」出现了两次。 |
| `analysis.column.unknown-alias` | 这个结果里没有「{alias}」。 |
| `analysis.config.malformed` | 这个分析没有可用的结构。 |
| `analysis.constant.not-finite` | 常量必须是有限的数字。 |
| `analysis.count.undeclared` | 这份数据不提供记录数。 |
| `analysis.date-diff.not-time` | 「{field}」不是时刻，不能算两个时刻之差。 |
| `analysis.date-diff.unit-unsupported` | 这里两个时刻之差不能以{unit}计。 |
| `analysis.derived.currency-invalid` | 这不是一个币种代码。 |
| `analysis.derived.currency-unknown` | 参与计算的指标不在同一个币种里：请指定它的币种。 |
| `analysis.derived.decimals` | 小数位是 0 到 {max} 的整数。 |
| `analysis.derived.format-invalid` | 算出的指标读作数字、百分比或金额。 |
| `analysis.derived.moment-operand` | 「{metric}」是时间点，不能参与计算。 |
| `analysis.derived.unknown-metric` | 派生指标引用了「{metric}」，它没有在它之前声明。 |
| `analysis.distinctCount.undeclared` | 「{field}」不提供去重计数。 |
| `analysis.element.out-of-chain` | 「{path}」的展开属于展开链的另一层。 |
| `analysis.element.undeclared` | 「{path}」的展开不可用。 |
| `analysis.elementFilter.empty` | 这组条件是空的，所以每个明细项都会展开。 |
| `analysis.elementFilter.incomplete` | 给「{field}」一个值，否则每个明细项都会展开。 |
| `analysis.elementFilter.search` | 搜索不能决定展开哪些明细项：把「{field}」从这个条件里去掉。 |
| `analysis.elements.too-many` | 这份数据的展开太多了。 |
| `analysis.expression.date-operand` | 「{field}」是日期，不能参与计算。 |
| `analysis.expression.divide-by-zero` | 这个表达式除以了零。 |
| `analysis.expression.malformed` | 这个指标没有可用的表达式。 |
| `analysis.expression.operand-unsupported` | 数据源不能用「{field}」计算。 |
| `analysis.expression.too-deep` | 表达式的嵌套超过了 {max} 层。 |
| `analysis.expression.too-many-nodes` | 表达式超过了 {max} 条。 |
| `analysis.expressions.undeclared` | 这份数据不提供计算表达式。 |
| `analysis.field.outside-scope` | 字段「{field}」不在展开后的范围里。 |
| `analysis.field.unknown` | 字段「{field}」在这里不可用。 |
| `analysis.first-last.order-by-required` | 展开明细项后，期初值、期末值要指定按哪个时间先后取。 |
| `analysis.first-last.undeclared` | 「{field}」不能取期初值或期末值。 |
| `analysis.function.unsupported` | 「{field}」不能求{fn}。 |
| `analysis.group.blank-missing-key` | 缺失值的占位文本是空的。 |
| `analysis.group.blank-time-zone` | 时区是空的。 |
| `analysis.group.dense-not-alone` | 补齐空档的时间维度只能是唯一的维度。 |
| `analysis.group.dense-unsupported` | 数据源不能给时间维度补齐空档。 |
| `analysis.group.interval-not-positive` | 数值区间的宽度必须大于零。 |
| `analysis.group.missing-key-unsupported` | 「{field}」不能为缺失值单独分一组，只有单值文本字段可以。 |
| `analysis.group.part-unsupported` | 这个维度不能{part}分组。 |
| `analysis.group.unit-unsupported` | 这个维度不能{unit}分组。 |
| `analysis.group.unsupported` | 「{field}」不能以「{type}」方式分组。 |
| `analysis.groups.too-many` | 这份数据的维度太多了。 |
| `analysis.having.malformed` | 这个结果筛选没有可用的结构。 |
| `analysis.having.metric-unsupported` | 数据源不能按「{metric}」保留分组。 |
| `analysis.having.requires-group` | 筛选结果至少需要一个维度。 |
| `analysis.having.too-deep` | 结果筛选的嵌套超过了 {max} 层。 |
| `analysis.having.too-many-nodes` | 结果筛选超过了 {max} 条。 |
| `analysis.having.undeclared` | 这份数据不提供筛选结果。 |
| `analysis.having.unknown-metric` | 筛选引用了「{metric}」，它不是可用的指标。 |
| `analysis.label.blank` | 显示名是空的。 |
| `analysis.limit.out-of-range` | 前 N 组须为 1～{max} 的整数。 |
| `analysis.metric.currency-unchecked` | {metric} 按每条记录自己的币种记账，但数据源核对不了币种，加起来的数可能混了几种货币；按「{field}」分组可以分开看。 |
| `analysis.metric.filter-field-unsupported` | 数据源不能在指标自己的条件里用「{field}」。 |
| `analysis.metric.out-of-reach` | 「{metric}」的时段不在所选日期内，这一列不适用，留空。 |
| `analysis.metric.type-unknown` | 指标类型 {type} 不可用。 |
| `analysis.metricFilter.empty` | 这组条件是空的，所以指标覆盖全部记录。 |
| `analysis.metricFilter.incomplete` | 给「{field}」一个值，否则指标覆盖全部记录。 |
| `analysis.metricFilter.not-scalar` | 「{field}」没有单一值供指标条件判断。 |
| `analysis.metrics.empty` | 分析至少需要一个指标。 |
| `analysis.metrics.too-many` | 这份数据的指标太多了。 |
| `analysis.percentile.out-of-range` | 百分位要在 0 与 100 之间，不含两端。 |
| `analysis.percentile.undeclared` | 「{field}」不提供百分位。 |
| `analysis.result.at-limit` | 只显示了前 {limit} 组，可能还有更多未列出。 |
| `analysis.result.mixed-currency` | {metric}：有的组的记录分属几种货币，这些组的金额加不到一起，不显示；按「{field}」分组可以分开看。 |
| `analysis.result.more-groups` | 只显示了前 {limit} 组，还有更多未列出。 |
| `analysis.sort.duplicate` | 排序已经按「{alias}」排过了。 |
| `analysis.sort.metric-unsupported` | 数据源只按维度给分组排序，不能按「{alias}」。 |
| `analysis.sort.requires-group` | 排序至少需要一个维度。 |
| `analysis.sort.too-many` | 结果最多按 {max} 个维度或指标排序。 |
| `analysis.sort.unknown-alias` | 排序依据的「{alias}」不在这个结果里。 |
| `analysis.split.whole-failed` | 没能把较小的系列并成「其他」，颜色会重复：{reason} |

### `binding.*` {#issues-binding}

| 代码 | 默认措辞 |
|---|---|
| `binding.definition.unknown` | 绑定指向 {id}，但没有哪份资源登记了它：它绑定的都不会生效。 |

### `capability.*` {#issues-capability}

| 代码 | 默认措辞 |
|---|---|
| `capability.analysis.count` | 数据源不提供记录数。 |
| `capability.analysis.dense` | 数据源不给时间维度补齐空档。 |
| `capability.analysis.element-unavailable` | 数据源不能在 {path} 的元素上聚合。 |
| `capability.analysis.expressions` | 数据源不接受公式与派生指标。 |
| `capability.analysis.field-narrowed` | 数据源不提供 {field} 上的 {dropped}。 |
| `capability.analysis.field-unavailable` | 数据源不能聚合 {field}。 |
| `capability.analysis.having` | 数据源不能按指标筛选分组。 |
| `capability.analysis.metric-sort` | 数据源只按维度给分组排序。 |
| `capability.analysis.unavailable` | 分析声明的聚合，数据源一项都做不了。 |
| `capability.descriptor.disagrees` | {source} 拒绝了一条它的能力描述 {version} 允许的查询（{code}）：服务与它的描述不一致。 |
| `capability.descriptor.unavailable` | 读不到 {source} 的能力描述，它的视图只按定义运行。 |
| `capability.field.alias` | 定义用别名 {field} 称呼 {path}：按 {path} 读取。 |
| `capability.field.deprecated` | 数据源已弃用 {field}。 |
| `capability.field.deprecated-because` | 数据源已弃用 {field}：{reason} |
| `capability.field.not-projectable` | 数据源不返回 {field}：这一列显示为空。 |
| `capability.field.operators-narrowed` | 数据源不接受 {field} 上的 {operators}。 |
| `capability.field.options-undescribed` | {field} 列出的 {values} 不在数据源声明的取值里。 |
| `capability.field.protected` | {field} 受保护：只显示，不能用来筛选、排序或检索。 |
| `capability.field.summary-narrowed` | 数据源算不了 {field} 的 {summaries}。 |
| `capability.field.temporal-mismatch` | {field} 声明为 {declared}，数据源却按 {described} 保存：它的日期条件会写错。 |
| `capability.field.unfilterable` | 数据源不接受 {field} 上的任何条件。 |
| `capability.field.unknown` | 数据源没有列出 {field}：照常显示，但条件、排序与汇总都不再用它。 |
| `capability.field.unsortable` | 数据源不能按 {field} 排序。 |
| `capability.record.cursor-appended` | 数据源给游标追加的是 {appended}，不是行键 {field}。 |
| `capability.record.paging` | 记录视图按 {paging} 翻页，数据源不提供这种翻页方式。 |
| `capability.record.row-key-unsortable` | 数据源不能按行键 {field} 排序。 |
| `capability.search.as-terms` | 数据源不支持按短语检索，{field} 改为按词检索。 |
| `capability.search.fields-narrowed` | 在这个数据源上，{field} 检索不了 {fields}。 |
| `capability.search.unavailable` | 数据源没有 {field} 能用的检索。 |

### `chart.*` {#issues-chart}

| 代码 | 默认措辞 |
|---|---|
| `chart.as-table` | 选定的图型画不了这个结果，先以表格显示。 |
| `chart.axis.scale-unknown` | 没有这种坐标轴刻度。 |
| `chart.boxplot.not-five-numbers` | 箱线图要同一字段、同一条件下的最小值、从低到高三个百分位与最大值。 |
| `chart.calendar.needs-day` | 日历热力图按天排：维度要是按日的日期。 |
| `chart.candlestick.needs-date` | K 线图沿日期维度排。 |
| `chart.candlestick.not-ohlc` | K 线图要同一字段、同一条件下的期初值、最大值、最小值与期末值。 |
| `chart.cartesian.percent-not-additive` | 百分比堆叠要可累加的指标（记录数或总和），「{metric}」不是。 |
| `chart.colors.invalid` | 这不是图表画得出的颜色。 |
| `chart.colors.malformed` | 钉住的图表颜色要按系列或类别逐个列出。 |
| `chart.combo.series-type-missing` | 组合图的每个系列都要有自己的类型。 |
| `chart.derived.kind-unknown` | 没有这种算出的线。 |
| `chart.derived.metric-not-drawn` | 算出的线跟的是「{metric}」，图上没有画它。 |
| `chart.derived.window` | 移动平均的期数要在 2 到 {max} 之间。 |
| `chart.family.missing` | {type} 图还没有 {family} 设置。 |
| `chart.funnel.duplicate-stage` | 漏斗的某个阶段列了两次。 |
| `chart.funnel.metrics-need-no-group` | 按指标分阶段的漏斗不能再有维度。 |
| `chart.funnel.not-additive` | 漏斗要一个可累加的指标（记录数或总和），「{metric}」不是。 |
| `chart.funnel.stages-need-category` | 漏斗的阶段取自类别维度，日期与数值区间不行。 |
| `chart.funnel.too-few-stages` | 漏斗至少要有两个阶段。 |
| `chart.gauge.empty-scale` | 刻度终点要大于起点。 |
| `chart.gauge.needs-no-group` | 刻度盘不能有维度。 |
| `chart.gauge.not-a-number` | 这里要一个数。 |
| `chart.group.unconsumed` | 图表没有用上维度「{alias}」。 |
| `chart.group.unknown` | 图表用到了一个这个分析没有的维度。 |
| `chart.heatmap.same-axes` | 热力图需要两个不同的轴。 |
| `chart.map.name-invalid` | 地图要有名字。 |
| `chart.map.needs-region` | 地图的地区要是一个维度的取值，不能是区间或日期。 |
| `chart.metric.moment` | 「{alias}」是时间点，图表不画它，也不拿它作比较。 |
| `chart.metric.needs-no-group` | 指标卡不能有维度。 |
| `chart.metric.trend-alias-mismatch` | 趋势必须用维度「{alias}」。 |
| `chart.metric.trend-needs-one-date-group` | 趋势正好需要一个时间维度。 |
| `chart.metric.trend-not-additive` | 趋势主数需要可累加的指标，或由可累加指标算出的指标（如客单价），「{metric}」不是。 |
| `chart.metric.unknown` | 图表用到了一个这个分析没有的指标。 |
| `chart.parallel.duplicate-metric` | 平行坐标图把同一个指标列了两次。 |
| `chart.parallel.too-few-metrics` | 平行坐标图要至少三个指标。 |
| `chart.pie.maxSlices-too-small` | 至少留两个扇区。 |
| `chart.pie.not-additive` | 饼图的扇区是整体的占比，要一个可累加的指标（记录数或总和），「{metric}」不是。 |
| `chart.radar.duplicate-metric` | 雷达图把同一个指标列了两次。 |
| `chart.radar.too-few-metrics` | 雷达图要至少三个指标。 |
| `chart.referenceBand.order` | 目标区间要从小的数到大的数。 |
| `chart.referenceLine.empty-axis` | 参考线所在的轴上要有系列。 |
| `chart.referenceLine.metric-not-drawn` | 参考线取的是「{metric}」，这根轴上没有画它。 |
| `chart.referenceLine.statistic-unknown` | 没有这种统计量。 |
| `chart.referenceLine.value-missing` | 参考线要站在一个数值或一个统计量上。 |
| `chart.sankey.not-additive` | 桑基图的流向加起来是流过的总量，所以要可加的指标（记录数或总和），不能是 {metric}。 |
| `chart.sankey.same-levels` | 桑基图把同一个维度列了两次。 |
| `chart.sankey.too-few-levels` | 桑基图要至少两个维度。 |
| `chart.sankey.too-many-levels` | 桑基图最多画四个维度。 |
| `chart.scatter.same-metrics` | 散点图需要两个不同的指标。 |
| `chart.splitBy.needs-one-series` | 拆分图只显示一个指标。 |
| `chart.splitBy.same-as-x` | 拆分不能重复横轴。 |
| `chart.sunburst.not-additive` | 旭日图的各部分加起来是上一层，所以要可加的指标（记录数或总和），不能是 {metric}。 |
| `chart.sunburst.same-levels` | 旭日图把同一个维度列了两次。 |
| `chart.sunburst.too-few-levels` | 旭日图要至少两个维度。 |
| `chart.sunburst.too-many-levels` | 旭日图最多画四个维度。 |
| `chart.themeRiver.needs-date` | 河流图沿日期维度流。 |
| `chart.themeRiver.not-additive` | 河流图把各条河流叠起来，所以要可加的指标（记录数或总和），不能是 {metric}。 |
| `chart.themeRiver.same-axes` | 河流图要两个不同的维度。 |
| `chart.tree.not-additive` | 树图的各部分加起来是上一层，所以要可加的指标（记录数或总和），不能是 {metric}。 |
| `chart.tree.same-levels` | 树图把同一个维度列了两次。 |
| `chart.tree.too-few-levels` | 树图要至少两个维度。 |
| `chart.tree.too-many-levels` | 树图最多画四个维度。 |
| `chart.treemap.not-additive` | 矩形树图的每一块是整体的一部分，要一个可累加的指标（记录数或总和），「{metric}」不是。 |
| `chart.treemap.same-levels` | 矩形树图的两层要是两个不同的维度。 |
| `chart.type.unknown` | 这个图表类型不可用。 |
| `chart.waterfall.not-additive` | 瀑布图把每一步加起来，要一个可累加的指标（记录数或总和），「{metric}」不是。 |

### `config.*` {#issues-config}

| 代码 | 默认措辞 |
|---|---|
| `config.filter.invalid` | 这个视图的条件无法读取。 |
| `config.filterMode.not-simple` | 这些条件要用高级编辑器才能完整显示。 |
| `config.filterMode.unknown` | 这个视图的筛选模式是未知的。 |
| `config.invalid` | 这个视图无法读取。 |
| `config.refresh.missing` | 这个视图没有刷新设置。 |
| `config.refresh.not-an-integer` | 刷新间隔必须是整秒。 |
| `config.refresh.too-long` | 刷新间隔不能超过 {max} 秒。 |
| `config.refresh.too-short` | 刷新间隔至少 {min} 秒。 |

### `dashboard.*` {#issues-dashboard}

| 代码 | 默认措辞 |
|---|---|
| `dashboard.binding.global-duplicate` | {field} 在这个面板上映射了两次。 |
| `dashboard.binding.global-unknown` | {field} 不是这个仪表盘的筛选字段。 |
| `dashboard.binding.kind-mismatch` | {global} 字段无法筛选 {panel} 字段。 |
| `dashboard.binding.missing` | 这个面板没有带 {field} 筛选，所以不能和其他面板一起筛选。 |
| `dashboard.binding.panel-unknown` | 「{filter}」接的字段，这个面板显示的视图里没有。 |
| `dashboard.click.board-dimension-unknown` | 点击要把 {field} 带到另一块仪表盘，这个面板已不按它分组，点一组会打开追问菜单。 |
| `dashboard.click.board-filter-mismatch` | 点击时要打开的仪表盘上的「{filter}」收不了 {field} 的值，点一组会打开追问菜单。 |
| `dashboard.click.board-filter-unknown` | 点击时要打开的仪表盘上已没有筛选「{filter}」，点一组会打开追问菜单。 |
| `dashboard.click.board-gone` | 点击时要打开的仪表盘已被删除，或你没有权限打开它，点一组会打开追问菜单。 |
| `dashboard.click.board-not-a-board` | 点击时要打开的不是一块仪表盘，点一组会打开追问菜单。 |
| `dashboard.click.board-source-unknown` | 点击要把这个仪表盘的筛选「{filter}」带到另一块仪表盘，但这个仪表盘上已没有它，点一组会打开追问菜单。 |
| `dashboard.click.destination-unavailable` | 点击时要去的视图已被删除，或你没有权限打开它。 |
| `dashboard.click.destination-unsupported` | 去视图时只能选记录视图或分析视图；要去仪表盘，选「仪表盘」。 |
| `dashboard.click.filter-ungrouped` | 「{filter}」接的是 {field}，这个面板不按它分组，点一组拿不到它的值，会打开追问菜单。 |
| `dashboard.click.filter-unknown` | 这个面板点击时要设置的筛选已经不在仪表盘上，点一组会打开追问菜单。 |
| `dashboard.click.filter-unwired` | 「{filter}」没有接到这个面板，点一组设置不了它，会打开追问菜单。 |
| `dashboard.click.invalid` | 这个面板的「点击时」设置读不出来，点一组会打开追问菜单。 |
| `dashboard.click.unpressable` | 这个面板没有可点的组（记录视图，或展开了明细项的分析），「点击时」的设置不生效。 |
| `dashboard.click.url-unknown-field` | 点击时去的地址用到了 {field}，这个面板不按它分组，点一组会打开追问菜单。 |
| `dashboard.click.url-unsafe` | 点击时去的地址不是 http、https、mailto 或本应用内的地址，点一组会打开追问菜单。 |
| `dashboard.click.view-not-a-view` | 点击时要去的不是记录视图或分析视图，点一组会打开追问菜单。 |
| `dashboard.click.view-undeclared` | 「{definition}」里没有点击时要去的视图，点一组会打开追问菜单。 |
| `dashboard.click.view-unknown` | 点击时要去的视图在这个应用里找不到，点一组会打开追问菜单。 |
| `dashboard.field.duplicate` | 筛选字段 {field} 声明了两次。 |
| `dashboard.field.name-empty` | 筛选字段需要一个名称。 |
| `dashboard.field.name-invalid` | {field} 不是可用的字段名。 |
| `dashboard.field.not-multiple` | 筛选 {field} 只能选一个值，这里有好几个。 |
| `dashboard.field.not-one-day` | 筛选 {field} 只能是一天：日历上的一天，或今天、昨天、前天。 |
| `dashboard.field.one-day-not-date` | 筛选 {field} 不是日期筛选，不能限定为一天。 |
| `dashboard.field.options-empty` | 筛选 {field} 从自己列的一组值里选，但这一组还是空的。 |
| `dashboard.field.required-no-default` | 筛选 {field} 是必填的，永远要有值，所以需要一个默认值。 |
| `dashboard.fields.too-many` | 一个仪表盘最多放 {max} 个筛选。 |
| `dashboard.filter.held` | 筛选 {field} 由页面设定，这里改不了。 |
| `dashboard.filter.unknown` | 这个仪表盘没有筛选 {field}。 |
| `dashboard.grid.unsupported` | 这个仪表盘的布局用的栅格在这里画不出来；面板按 {columns} 列摆放。 |
| `dashboard.grouping.kept` | 这个面板保留自己的时间粒度：它的数据不能按仪表盘选的粒度分组。 |
| `dashboard.grouping.unit-duplicate` | 时间粒度里 {unit} 出现了两次。 |
| `dashboard.grouping.unit-unknown` | {unit} 不是这个仪表盘提供的时间粒度。 |
| `dashboard.grouping.units-empty` | 时间粒度里没有可选的粒度。 |
| `dashboard.heading.too-long` | 一个标题最多 {max} 个字符。 |
| `dashboard.layout.invalid` | 这个面板的位置或尺寸不可用。 |
| `dashboard.layout.missing` | 这个面板没有位置。 |
| `dashboard.layout.out-of-grid` | 这个面板超出了网格的 {columns} 列。 |
| `dashboard.link.label-empty` | 链接需要一个标签。 |
| `dashboard.links.too-many` | 链接面板最多放 {max} 个链接。 |
| `dashboard.markdown.too-long` | 一段文字最多 {max} 个字符。 |
| `dashboard.panel.definition-unknown` | 这个面板里的分析所用的数据已经不可用了。 |
| `dashboard.panel.failed` | 这个面板显示的视图没能打开。 |
| `dashboard.panel.id-duplicate` | 两个面板共用了 id {id}。 |
| `dashboard.panel.id-empty` | 面板需要一个 id。 |
| `dashboard.panel.kind-unsupported` | 这个面板指向的不是记录视图或分析视图。 |
| `dashboard.panel.not-owned` | 只有只属于这个仪表盘的分析才能另存为视图。 |
| `dashboard.panel.not-referenced` | 这个面板显示的不是你打开着的已保存视图，没有可复制的东西。 |
| `dashboard.panel.opens-elsewhere` | 这个面板设定在工作台中打开的「{view}」看的是「{definition}」，不是这个面板的数据，改为打开面板自己的视图。 |
| `dashboard.panel.opens-invalid` | 这个面板设定在工作台中打开的视图写得不对，改为打开面板自己的视图。 |
| `dashboard.panel.opens-undeclared` | 「{definition}」里没有这个面板设定在工作台中打开的视图，改为打开面板自己的视图。 |
| `dashboard.panel.opens-unknown` | 这个面板设定在工作台中打开的视图在这个应用里找不到，改为打开面板自己的视图。 |
| `dashboard.panel.owned-invalid` | 这个面板里的分析不是预期的结构。 |
| `dashboard.panel.presentation-dropped` | 这个面板改过的展示已经不适用于它的视图，按视图本来的样子显示。 |
| `dashboard.panel.scope-too-narrow` | 这个面板显示的视图并不对这个仪表盘的所有读者开放。 |
| `dashboard.panel.source-invalid` | 这个面板要么显示一个已保存的视图，要么显示只属于这个仪表盘的分析。 |
| `dashboard.panel.tab-unknown` | 这个面板不在这个仪表盘的任何标签页上，先显示在第一个标签页。 |
| `dashboard.panel.unavailable` | 这个面板显示的视图已被删除，或者你没有查看权限。 |
| `dashboard.panel.unknown-kind` | 这种面板类型不可用。 |
| `dashboard.panel.view-undeclared` | 「{definition}」里没有这个面板要显示的视图。 |
| `dashboard.panel.view-unknown` | 这个面板要显示的视图在这个应用里找不到。 |
| `dashboard.panels.too-many` | 一个仪表盘最多放 {max} 个面板。 |
| `dashboard.scope.unsupported` | 仪表盘不接受外部条件：请改为锁定或隐藏它的筛选。 |
| `dashboard.shape.invalid` | 仪表盘的这一部分不是预期的结构。 |
| `dashboard.system.non-system-panels` | 这些面板引用了非系统视图，先把它们发布为系统视图：{panels} |
| `dashboard.tab.id-duplicate` | 两个标签页共用了 id {id}。 |
| `dashboard.tab.id-empty` | 标签页需要一个 id。 |
| `dashboard.tab.title-empty` | 有一个标签页没有名字，按它的位置称呼。 |
| `dashboard.tabs.too-many` | 一个仪表盘最多放 {max} 个标签页。 |
| `dashboard.url.unsupported-scheme` | 只能显示 http、https、mailto 和相对链接。 |
| `dashboard.width.unknown` | 这个仪表盘的宽度「{width}」无法识别，先按全宽显示；编辑时可以选固定宽度或全宽。 |

### `definition.*` {#issues-definition}

| 代码 | 默认措辞 |
|---|---|
| `definition.analysis.date-part-not-temporal` | {field} 提供了按星期、时段分组，但它存的不是日期或时间。 |
| `definition.analysis.date-part-unknown` | {field} 声明了未知的周期：{value}。 |
| `definition.analysis.default-limit-too-large` | 默认组数上限超过了最大值。 |
| `definition.analysis.element-field-unknown` | {path} 没有声明名为 {field} 的字段。 |
| `definition.analysis.element-undeclared` | 分析展开了 {path}，它不是存放条目的字段。 |
| `definition.analysis.elements-too-many` | 分析声明的嵌套层数超过了 {max} 层。 |
| `definition.analysis.field-unknown` | 分析能力写了 {field}，定义没有声明它。 |
| `definition.analysis.limit-invalid` | 分析上限必须是正整数。 |
| `definition.analysis.no-metric` | 分析能力没有给出可以起步的指标。 |
| `definition.analysis.wider` | 数据源的分析不提供 {what}。 |
| `definition.descriptor.missing` | 没有给数据源 {source} 的描述，它的能力没有核对。 |
| `definition.field.analysis-wider` | {field} 在数据源上不能用于分析的 {what}。 |
| `definition.field.cell-invalid` | {field} 声明了未知的单元格读法：{value}。 |
| `definition.field.deprecated` | {field} 已被数据源弃用（{message}），保留它请写明理由。 |
| `definition.field.duplicate` | 字段 {field} 声明了两次。 |
| `definition.field.editor-removed` | {field} 声明了 editor，这个成员已经没有了，删掉即可。 |
| `definition.field.element-search-fields-required` | {field} 在条目里搜索，要写明它搜条目的哪些字段。 |
| `definition.field.element-title-not-a-value` | {field} 以 {title} 作为元素的标题，但它不持有可读的值。 |
| `definition.field.element-title-unknown` | {field} 以 {title} 作为元素的标题，但它的元素没有声明这个字段。 |
| `definition.field.kind-unknown` | 描述没有说 {field} 存的是哪种值，请给它写 kind。 |
| `definition.field.kind-unregistered` | {field} 用了未注册的类型 {kind}。 |
| `definition.field.name-invalid` | {field} 不是可用的字段名。 |
| `definition.field.not-elements` | {field} 不是数据源列出条目的数组。 |
| `definition.field.numeric-invalid` | {field} 声明的数值格式引擎写不出来：{value}。 |
| `definition.field.operator-wider` | {field} 在数据源上不收 {operator}。 |
| `definition.field.search-fields-unknown` | {field} 搜索 {missing}，定义没有声明它。 |
| `definition.field.search-mode-invalid` | {field} 声明了未知的搜索模式：{value}。 |
| `definition.field.sort-wider` | {field} 在数据源上不能排序。 |
| `definition.field.string-comparison-invalid` | {field} 声明了未知的文本比较方式：{value}。 |
| `definition.field.summary-wider` | {field} 在数据源上不能按 {fn} 汇总。 |
| `definition.field.temporal-invalid` | {field} 声明的时间存储方式引擎写不出来：{value}。 |
| `definition.field.temporal-misplaced` | {field} 声明了时间存储方式，但它的类型 {kind} 不写时间。 |
| `definition.field.time-precision-invalid` | {field} 声明了未知的时间精度：{value}。 |
| `definition.field.time-precision-misplaced` | {field} 声明了时间精度，但它读作 {cell}，没有时刻。 |
| `definition.field.tone-invalid` | {field} 声明了未知的选项语气：{value}。 |
| `definition.field.undescribed` | 数据源的描述里没有 {field} 这个路径。 |
| `definition.field.unlabelled` | {field} 没有写名字，只能按描述或路径显示。 |
| `definition.fieldGroup.duplicate` | 分组 {group} 声明了两次。 |
| `definition.fieldGroup.field-duplicate` | 分组 {group} 列了 {field}，它已经列在另一个分组下。 |
| `definition.fieldGroup.field-unknown` | 分组 {group} 列了 {field}，定义没有声明它。 |
| `definition.fieldGroup.invalid` | 字段分组需要 id 和标签。 |
| `definition.id.separator` | 定义 id 不能带 {separator}。 |
| `definition.option.undescribed` | {value} 不是数据源为 {field} 列出的值。 |
| `definition.record.layouts-empty` | 记录能力没有给出布局。 |
| `definition.record.max-sort-fields-invalid` | 排序字段上限必须是整数个字段，而不是 {value}。 |
| `definition.record.max-window-cursor` | 分页窗口限制的是页码，而这个源按游标翻页。 |
| `definition.record.max-window-invalid` | 分页窗口必须是大于零的整数行数，而不是 {value}。 |
| `definition.record.paging-wider` | 数据源不按 {paging} 分页。 |
| `definition.record.row-field-not-a-path` | 行字段 {field} 是搜索或元数据句柄，不是一行里的内容。 |
| `definition.record.row-field-unknown` | 行字段 {field} 不是已声明的字段。 |
| `definition.record.row-key-unknown` | 行键 {field} 不是已声明的字段。 |
| `definition.record.row-key-unsortable` | 行键 {field} 必须可排序：每一页最后都按它排，同一行才不会出现在两页上。 |
| `definition.source.unregistered` | 没有为 {source} 登记数据源：注册这份定义时带上它的数据源。 |
| `definition.text.fallback` | 当下的措辞没有给出键 {key}，改用引擎起始的措辞说出。 |
| `definition.text.unknown` | 键 {key} 没有给出措辞。 |
| `definition.timeField.not-time` | 时间字段 {field} 存的不是时刻。 |
| `definition.timeField.unknown` | 时间字段 {field} 不是这个定义的字段。 |
| `definition.view.analysis-open` | 这张视图要的分析由数据源给：快照里没有，只在给出分析能力的数据源上打得开。 |
| `definition.view.id-duplicate` | 两个视图共用了 id {id}。 |
| `definition.view.id-separator` | 视图 id 不能带 {separator}。 |
| `definition.view.kind-mismatch` | {kind} 视图不属于 {definition} 定义。 |
| `definition.view.owned-invalid` | 面板 {panel} 自带的分析不完整或设置有误。 |

### `export.*` {#issues-export}

| 代码 | 默认措辞 |
|---|---|
| `export.failed` | 导出失败：{reason} |

### `filter.*` {#issues-filter}

| 代码 | 默认措辞 |
|---|---|
| `filter.element.duration` | 「距另一时刻」不能用在明细项的条件里。 |
| `filter.element.root-filter` | {field} 问的是整条记录，不能拿来问其中一个条目。 |
| `filter.field.duplicate-in-group` | {field} 已经是这个分组里的条件。要再问它别的，嵌套一个分组。 |
| `filter.field.holds-no-elements` | 「{field}」没有可匹配的条目。 |
| `filter.field.reference-without-source` | {field} 是引用字段，但没有声明候选来源。 |
| `filter.field.unknown` | 字段「{field}」已不存在。 |
| `filter.group.unknown-operator` | 条件分组只能是 AND 或 OR。 |
| `filter.kind.failed` | 「{label}」这个条件没能检查，请联系维护这个视图的人。 |
| `filter.kind.unknown-editor` | {kind} 类型要的 {input} 编辑器，引擎没有。 |
| `filter.kind.unregistered` | {kind} 类型没有注册编辑器。 |
| `filter.node.invalid` | 这个条件无法读取。 |
| `filter.operator.unsupported` | 「{field}」不支持「{operator}」。 |
| `filter.presence.empty-is-missing` | 在这个数据源上，「{field}」为空也算没有值。 |
| `filter.tree.too-deep` | 条件的嵌套超过了 {max} 层。 |
| `filter.tree.too-many-nodes` | 条件超过了 {max} 条。 |
| `filter.value.duration-from-not-time` | 「{field}」存的不是时刻。 |
| `filter.value.duration-from-unknown` | 这里没有「{field}」这个字段。 |
| `filter.value.duration-same-time` | 一个时刻不能距它自己。 |
| `filter.value.expected-boolean` | 选择是或否。 |
| `filter.value.expected-date` | 输入一个日期。 |
| `filter.value.expected-deletion-state` | 选择看哪些记录：仅未删除、仅已删除或含已删除。 |
| `filter.value.expected-duration` | 「距另一时刻」要选早一点的时刻、比较、时长和单位。 |
| `filter.value.expected-entry-list` | 选择或输入一个以上条目。 |
| `filter.value.expected-id` | 输入一个 id，或挑一个候选。 |
| `filter.value.expected-id-list` | 输入一个或多个 id。 |
| `filter.value.expected-number` | 输入一个数字。 |
| `filter.value.expected-number-list` | 输入一个或多个数字。 |
| `filter.value.expected-number-range` | 输入两个数字组成的范围。 |
| `filter.value.expected-option-list` | 选择一个或多个选项。 |
| `filter.value.expected-predicate` | 说明一个条目要满足什么。 |
| `filter.value.expected-reference-list` | 选择一条或多条记录。 |
| `filter.value.expected-string` | 输入一个值。 |
| `filter.value.expected-string-list` | 输入一个或多个值。 |
| `filter.value.expected-text` | 输入要搜索的内容。 |
| `filter.value.expects-one` | 这个条件只接受一个值。 |
| `filter.value.inverted-range` | 范围的起点晚于终点。 |
| `filter.value.relative-too-large` | 相对时间窗最多跨 {max} 个单位。 |
| `filter.value.required` | 这个条件需要一个值。 |
| `filter.value.unknown-option` | {values} 已经不是选项了。 |
| `filter.value.unknown-time-zone` | {timeZone} 不是这个浏览器认识的时区。 |
| `filter.value.unparsable-date` | 这个日期读不出来。 |

### `record.*` {#issues-record}

| 代码 | 默认措辞 |
|---|---|
| `record.capability.missing` | {definition} 不再提供记录视图。 |
| `record.card.invalid` | 卡片设置无法读取。 |
| `record.column.duplicate` | 列「{field}」列了两次。 |
| `record.column.hidden-invalid` | 列 {field} 的隐藏值是 {hidden}，那不是隐藏一列的写法。 |
| `record.column.pin-invalid` | 列 {field} 的固定值是 {pinned}，那不是固定一列的写法。 |
| `record.column.width-invalid` | 列「{field}」的宽度是 {width}，那不是像素数。 |
| `record.detail.failed` | 读不到完整记录：{reason} |
| `record.detail.forbidden` | 你没有权限查看这条记录。 |
| `record.field.not-a-column` | 「{field}」是搜索或元数据句柄，不是一行里的内容。 |
| `record.field.unknown` | 列「{field}」已不存在。 |
| `record.filter.required` | 这个数据源只在有条件时列出记录：先添加一个条件。 |
| `record.layout.unsupported` | 这里没有「{layout}」布局。 |
| `record.pageSize.not-positive` | 每页条数必须是正数。 |
| `record.pageSize.too-large` | 每页条数不能超过 {max}。 |
| `record.sort.direction-invalid` | 「{field}」的排序方向既不是升序也不是降序。 |
| `record.sort.duplicate` | 排序已经按「{field}」排过了。 |
| `record.sort.invalid` | 排序设置无法读取。 |
| `record.sort.not-sortable` | 「{field}」不能用来排序。 |
| `record.sort.parallel-arrays` | 数据源不能同时按 {fields} 排序：只留其中一个。 |
| `record.sort.too-many` | 游标视图最多按 {max} 个字段排序。 |
| `record.summaries.invalid` | 汇总设置无法读取。 |
| `record.summary.duplicate` | 「{field}」的{fn}汇总列了两次。 |
| `record.summary.unsupported` | 「{field}」不提供{fn}汇总。 |
| `record.table.invalid` | 表格设置无法读取。 |

### `runtime.*` {#issues-runtime}

| 代码 | 默认措辞 |
|---|---|
| `runtime.kind.not-declared` | {definition} 不提供 {kind} 视图。 |
| `runtime.options.unresolved` | {source} 没有配置候选来源。 |
| `runtime.query.failed` | 没能加载数据：{reason} |
| `runtime.query.failed.any_requires_single_value` | 「任一值」只能用于单值字段。 |
| `runtime.query.failed.array_equality` | 这里不能拿「{field}」和整个列表比较，请改为匹配其中的元素。 |
| `runtime.query.failed.body_not_object` | 服务端读不懂这个视图发出的查询：{reason} |
| `runtime.query.failed.count_requires_filter` | 先添加一个条件：服务端不会统计全部记录。 |
| `runtime.query.failed.cursor_not_allowed` | 「{field}」不能用来翻页。 |
| `runtime.query.failed.cursor_sort_duplicate` | 排序里「{field}」出现了两次，保留一次即可。 |
| `runtime.query.failed.cursor_sort_too_many` | 排序字段太多，没法在这份数据上翻页；去掉几个。 |
| `runtime.query.failed.element_scope_required` | 「{field}」只能在它所属的列表里，按列表元素的条件筛选。 |
| `runtime.query.failed.empty_body` | 服务端收到的是空查询：{reason} |
| `runtime.query.failed.event_projection_type_required` | 事件内容不能脱离它的类型显示；保留事件类型这一列。 |
| `runtime.query.failed.expensive_operator_disabled` | 服务端在这里不允许这种高代价的查询：{reason} |
| `runtime.query.failed.explicit_entry_required` | 查询没有指明要在哪个入口上运行：{reason} |
| `runtime.query.failed.filter_too_large` | 条件超出了服务端一次查询能接受的数量：{reason} |
| `runtime.query.failed.first_last_requires_order_by` | 「{field}」的「首个值」「最后一个值」在这里要指定排序字段：这个位置没有事件时间可排。 |
| `runtime.query.failed.first_last_requires_single_value` | 「首个值」「最后一个值」只能用于单值字段，「{field}」可能有多个值。 |
| `runtime.query.failed.identity_undefined` | 这份数据没有定义记录标识，不能逐页翻阅或按标识选取。 |
| `runtime.query.failed.incomplete_projection` | 服务端这里给不出完整记录，请选择要显示的列。 |
| `runtime.query.failed.invalid_cursor` | 这一页没法接着往下翻了，请从第一页重新开始。 |
| `runtime.query.failed.invalid_json` | 服务端读不懂这个视图发出的查询：{reason} |
| `runtime.query.failed.invalid_request` | 服务端拒绝了这条查询：{reason} |
| `runtime.query.failed.invalid_value` | 查询里有个值缺了或类型不对：{reason} |
| `runtime.query.failed.metric_filter_array_field` | 指标自己的条件不能用列表字段「{field}」。 |
| `runtime.query.failed.metric_filter_element_match` | 指标自己的条件不能按列表元素匹配。 |
| `runtime.query.failed.metric_filter_search` | 指标自己的条件不能用搜索。 |
| `runtime.query.failed.missing_key_requires_string` | 只有单个文本字段才能把缺值归为一组，「{field}」不是。 |
| `runtime.query.failed.model_search_unsupported` | 这份数据不支持搜索。 |
| `runtime.query.failed.not_collection` | 「{field}」不是列表，不能判断是否为空。 |
| `runtime.query.failed.not_projectable` | 「{field}」不能作为这份数据的列显示。 |
| `runtime.query.failed.not_single_string` | 「{field}」不是单个文本，不能判断是否为空。 |
| `runtime.query.failed.parallel_array_sort` | 「{field}」与排序里的另一个列表字段不能同时排序，只保留其中一个。 |
| `runtime.query.failed.protected_aggregation` | 「{field}」受保护，不能用来汇总。 |
| `runtime.query.failed.protected_comparison` | 「{field}」受保护，不能用来筛选、排序或搜索。 |
| `runtime.query.failed.residual_groups_exceeded` | 这份汇总的分组太多，服务端没法按指标筛选或排序；先缩小数据范围。 |
| `runtime.query.failed.size_out_of_range` | 服务端限制了一次能读多少行：{reason} |
| `runtime.query.failed.sort_field_duplicate` | 「{field}」排了两次，保留一次即可。 |
| `runtime.query.failed.sort_too_many` | 排序字段太多，去掉几个。 |
| `runtime.query.failed.storage_unsupported` | 服务端的存储运行不了这条查询：{reason} |
| `runtime.query.failed.temporal_aggregation_unsupported` | 「{field}」没有按日期或时间戳存时间，不能按时间分组或计算时间差。 |
| `runtime.query.failed.temporal_configuration_conflict` | 「{field}」上的相对时间与这个字段记时间的方式不一致：{reason} |
| `runtime.query.failed.temporal_representation_required` | 「{field}」存的不是已知的日期或时间，不能按相对时间筛选。 |
| `runtime.query.failed.unknown_field` | 服务端不认识字段「{field}」。视图可能比数据新；去掉用到它的条件、列或维度。 |
| `runtime.query.failed.unknown_property` | 服务端不认识这条查询的一部分，可能版本比视图引擎旧：{reason} |
| `runtime.query.failed.unknown_type` | 服务端不认识这个视图用到的运算或指标，可能版本比视图引擎旧：{reason} |
| `runtime.query.failed.unknown_value` | 服务端不认识这个视图发出的某个取值：{reason} |
| `runtime.query.failed.unsupported_capability` | 「{field}」在这份数据上不能这样用：{reason} |
| `runtime.query.failed.value_mismatch` | 有个值不是「{field}」能取的值。 |
| `runtime.query.forbidden` | 无权限查看这些数据。 |
| `runtime.query.queue-full` | 同时查询太多，过一会儿再试。 |
| `runtime.query.too-many-nodes` | 这些条件组成的查询有 {count} 个节点，数据源最多收 {max} 个。请删掉一些条件。 |
| `runtime.query.too-many-values` | 有一个条件列了 {count} 个值，数据源一个条件最多收 {max} 个。 |
| `runtime.query.unreachable` | 没能加载数据：无法连接服务端。 |
| `runtime.source.unresolved` | {source} 没有注册数据源。 |
| `runtime.summary.page-only` | 合计查询失败，表尾只汇总了本页的行。 |

### `view.*` {#issues-view}

| 代码 | 默认措辞 |
|---|---|
| `view.abandon.failed` | 无法放弃那次写入。 |
| `view.change.notify-failed` | 视图变化的订阅者出错：{reason}。列表可能慢一拍。 |
| `view.changeAudience.failed` | 没能改变这个视图的可见范围。 |
| `view.changeAudience.forbidden` | 你不能改变这个视图的可见范围，另存一份自己的吧。 |
| `view.changeAudience.invalid` | 没能改变这个视图的可见范围：{reason} |
| `view.changeAudience.invalid.shared-boards` | 这个视图仍是共享的：共享仪表盘显示着它（{boards}）。 |
| `view.changeAudience.unsupported` | 当前的视图存储不支持在个人与共享之间移动视图。 |
| `view.config.invalid` | 先修正这个视图报出的问题，再保存。 |
| `view.config.too-large` | 这个视图太大，存不下：{size} KB，上限是 {max} KB。 |
| `view.create.forbidden` | 你不能在这里创建视图。 |
| `view.definition.invalid` | {id} 定义有 {issues} 个问题，无法打开。 |
| `view.definition.not-found` | 没有名为 {id} 的定义。 |
| `view.delete.failed` | 这个视图删不掉。 |
| `view.delete.forbidden` | 你不能删除这个视图，请联系它的归属人。 |
| `view.field.deprecated` | 这个视图用到了「{field}」，数据源已弃用它。 |
| `view.field.deprecated-because` | 这个视图用到了「{field}」，数据源已弃用它：{reason} |
| `view.list.failed` | 视图列表加载失败。 |
| `view.list.failed.unavailable` | 视图列表加载失败：无法连接服务端。 |
| `view.list.failed.unavailable.server` | 视图列表加载失败：服务端暂时无法处理（{reason}）。 |
| `view.list.failed.unsupported` | 视图列表加载失败：服务端未提供视图存储。 |
| `view.list.reserved-id` | 存储的视图 {id} 用了保留 id，已跳过。 |
| `view.open.failed` | 无法打开这个视图。 |
| `view.open.failed.forbidden` | 你没有这个视图的权限，请联系管理员开通。 |
| `view.open.failed.not_found` | 这个视图已不存在。 |
| `view.open.failed.unavailable` | 无法加载这个视图：无法连接服务端。 |
| `view.open.failed.unavailable.server` | 无法加载这个视图：服务端暂时无法处理（{reason}）。 |
| `view.open.failed.unsupported` | 无法加载这个视图：服务端未提供视图存储。 |
| `view.open.not-found` | 没有名为 {id} 的视图。 |
| `view.open.other-definition` | 这个视图属于另一个页面，这个页面无法显示。 |
| `view.open.wrong-kind` | 这个视图是另一种类型（{kind}），这个页面无法显示。 |
| `view.preferences.default-forbidden` | 你不能设置默认视图。 |
| `view.preferences.failed` | 视图偏好保存失败。 |
| `view.preferences.load-failed` | 视图偏好加载失败，视图按服务端顺序显示。 |
| `view.preferences.reorder-forbidden` | 你不能给视图排序。 |
| `view.publish.failed` | 这个视图没能发布为系统视图。 |
| `view.publish.forbidden` | 你没有发布系统视图的权限，请联系管理员。 |
| `view.rename.failed` | 视图改名失败。 |
| `view.rename.forbidden` | 你不能给这个视图改名，另存一份自己的吧。 |
| `view.resolve.failed` | 冲突未能解决。 |
| `view.retry.failed` | 重试失败。 |
| `view.runtime.not-owned` | 这个视图在这里已经不是打开着的了。 |
| `view.save-as.failed` | 这个视图存不成副本。 |
| `view.save.failed` | 视图保存失败。 |
| `view.save.forbidden` | 你不能保存对这个视图的改动，另存一份自己的吧。 |
| `view.system.read-only` | 系统视图不能改（{action}）。 |
| `view.title.empty` | 视图需要一个标题。 |
| `view.title.too-long` | 视图标题最多 {max} 个字。 |
| `view.write.conflict` | 别人先保存了这个视图。 |
| `view.write.conflict-unreadable` | 别人先保存了，且最新版本无法读取。 |
| `view.write.forbidden` | 你不能写这个视图。 |
| `view.write.in-flight` | 这个视图正在保存，等它结束。 |
| `view.write.invalid` | 服务端拒绝了这次写入：{reason} |
| `view.write.not-a-conflict` | 那次写入是 {kind}，不是冲突。 |
| `view.write.not-pending` | 那次写入已经结清了。 |
| `view.write.not_found` | 这个视图已不存在。 |
| `view.write.storage` | 这个浏览器没能把这次修改存下来（存储已满或被禁用）。 |
| `view.write.unavailable` | 无法连接服务端。 |
| `view.write.unavailable.server` | 服务端暂时无法处理这次写入：{reason} |
| `view.write.unknown-pending` | 上一次 {action} 还没有确认。先重试或搁置它，再写下一次。 |
| `view.write.unsupported` | 服务端未提供视图存储。 |

:::

<!-- issue-codes:end -->

## 完整可运行版本

- 清单与它的测试：[`test/surface/issues.txt`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/surface/issues.txt)、[`test/fixtures/issueCodes.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/fixtures/issueCodes.ts)。
- 措辞目录：[`src/ui/messages/zh-CN.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/messages/zh-CN.ts)；`Issue` 在 [`src/model/issue.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/model/issue.ts)。
