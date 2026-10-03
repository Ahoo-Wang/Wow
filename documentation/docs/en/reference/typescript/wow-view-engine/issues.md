---
title: 'Issue Codes'
description: 'Every issue code the view engine reports, with its default wording, generated from the public surface list — @ahoo-wang/wow-view-engine'
---

# Issue Codes

Every kernel of the engine reports a problem in one shape: `Issue`. Wording is not in the model: `code` is the stable key to branch on, `params` carries the values a sentence needs, and the UI layer looks the sentence up in the catalogue by `code`. So a host can branch on a `code` (to handle one kind of failure its own way) and reword by key ([wording](./host#api-MessagesProvider)).

Guides: [Writing a Definition](../../../guide/typescript/view-engine-definitions.md) (reading and fixing what `admit` reports); [Fitting the View Engine into a Host](../../../guide/typescript/view-engine-host.md#messages) (rewording by key).

## Issue {#api-Issue}

- `severity`: `error` blocks apply and save; `warning` is reported without blocking — something is wrong, or the answer could be misread; `note` is something true about the answer a reader should know, with nothing wrong — the groups a view's own limit left out, say.
- `path`: where it is in the config, such as `['sort', 0, 'field']`.
- `params`: the values a sentence needs. A number is a quantity, printed the way every other number on the surface is; a number that is a name (an id, a year, a row key) is handed over as a string and left as it came.

`/ui`'s `formatIssue(messages, issue, locale?)` says one issue as a sentence; `formatMessage` fills one key's params.

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

## Codes are public surface

The tables below are `test/surface/issues.txt` — every code the engine can raise — with the default wording the English catalogue gives each. Renaming or removing a code is a breaking change like removing an export, made only in a minor release and listed in its release notes; adding one is free.

The list holds the codes `issue('…')` raises directly and those raised through a helper: `defineView`'s admission findings (`definition.field.undescribed`, `definition.field.unlabelled`, the `definition.field.*-wider` warnings and the rest), a board click's warnings, the base code of a failed command (`view.open.failed` and its kin).

**A failed command's code may carry a suffix.** When the store or a write fails, the engine appends what happened to the command's base code: the store's outcome `.conflict`, `.not_found`, `.forbidden`, `.invalid`, `.unavailable`, `.unavailable.server`, `.unsupported`, or the write's state `.conflict`, `.unknown` — `view.open.failed.not_found`, for one. The tables list only the combinations the catalogue words on their own; the rest fall back along the dots to the base code's sentence. So branch on a failure by prefix: `issue.code.startsWith('view.open.failed')`.

`{field}`, `{max}` and the like in the wording are `params` placeholders; plural forms such as `-one` are not codes, and a count picks them.

<!-- Generated region: from test/surface/issues.txt and the en catalogue.
     Regenerate with: pnpm --filter @ahoo-wang/wow-view-engine reference:docs -->

<!-- issue-codes:begin -->

::: v-pre

### `analysis.*` {#issues-analysis}

| Code | Default wording |
|---|---|
| `analysis.alias.duplicate` | The display name {alias} is used twice. |
| `analysis.alias.invalid` | {alias} is not a usable display name. |
| `analysis.alias.not-a-segment` | {alias} cannot contain a dot. |
| `analysis.alias.reserved` | {alias} uses a reserved prefix. |
| `analysis.any.undeclared` | {field} cannot be shown as a sample value. |
| `analysis.capability.missing` | {definition} does not offer an analysis view any more. |
| `analysis.column.duplicate` | This result lists {alias} twice. |
| `analysis.column.unknown-alias` | There is no {alias} in this result. |
| `analysis.config.malformed` | This analysis has no usable shape. |
| `analysis.constant.not-finite` | A constant must be a finite number. |
| `analysis.count.undeclared` | This dataset does not offer a record count. |
| `analysis.date-diff.not-time` | {field} holds no time a duration could run from or to. |
| `analysis.date-diff.unit-unsupported` | A time between two moments cannot be measured in {unit} here. |
| `analysis.derived.currency-invalid` | That is not a currency code. |
| `analysis.derived.currency-unknown` | Its operands are in no one currency: choose the currency it is in. |
| `analysis.derived.decimals` | Decimals are a whole number from 0 to {max}. |
| `analysis.derived.format-invalid` | A calculated metric reads as a number, a percent or money. |
| `analysis.derived.moment-operand` | {metric} is a point in time and cannot be calculated with. |
| `analysis.derived.unknown-metric` | The derived metric refers to {metric}, which is not declared before it. |
| `analysis.distinctCount.undeclared` | {field} does not offer distinct counts. |
| `analysis.element.out-of-chain` | The expansion of {path} belongs to another level of the chain. |
| `analysis.element.undeclared` | The expansion of {path} is not available. |
| `analysis.elementFilter.empty` | This filter has no conditions, so every item is expanded. |
| `analysis.elementFilter.incomplete` | Give {field} a value, or every item is expanded. |
| `analysis.elementFilter.search` | A search cannot decide which items are expanded: take {field} out of this condition. |
| `analysis.elements.too-many` | Too many expansions for this dataset. |
| `analysis.expression.date-operand` | {field} is a date and cannot be calculated with. |
| `analysis.expression.divide-by-zero` | This expression divides by zero. |
| `analysis.expression.malformed` | This metric has no usable expression. |
| `analysis.expression.operand-unsupported` | The source cannot calculate with {field}. |
| `analysis.expression.too-deep` | The expression nests deeper than {max} levels. |
| `analysis.expression.too-many-nodes` | The expressions exceed {max} entries. |
| `analysis.expressions.undeclared` | This dataset does not offer computed expressions. |
| `analysis.field.outside-scope` | The field {field} sits outside the expanded scope. |
| `analysis.field.unknown` | The field {field} is not available here. |
| `analysis.first-last.order-by-required` | Inside expanded entries, an opening or closing value needs a time to order the entries by. |
| `analysis.first-last.undeclared` | {field} offers no opening or closing value. |
| `analysis.function.unsupported` | {field} cannot be summarised as {fn}. |
| `analysis.group.blank-missing-key` | The placeholder for missing values is empty. |
| `analysis.group.blank-time-zone` | The time zone is empty. |
| `analysis.group.dense-not-alone` | A gap-filling time dimension must be the only dimension. |
| `analysis.group.dense-unsupported` | The source cannot fill the gaps of a time dimension. |
| `analysis.group.interval-not-positive` | A number range must be wider than zero. |
| `analysis.group.missing-key-unsupported` | {field} cannot hold a bucket for missing values; only single-valued text fields can. |
| `analysis.group.part-unsupported` | This dimension cannot group this way: {part}. |
| `analysis.group.unit-unsupported` | This dimension cannot group this way: {unit}. |
| `analysis.group.unsupported` | {field} cannot be grouped this way: {type}. |
| `analysis.groups.too-many` | Too many dimensions for this dataset. |
| `analysis.having.malformed` | This result filter has no usable shape. |
| `analysis.having.metric-unsupported` | The source cannot keep groups by {metric}. |
| `analysis.having.requires-group` | Filtering the result needs at least one dimension. |
| `analysis.having.too-deep` | The result filter nests deeper than {max} levels. |
| `analysis.having.too-many-nodes` | The result filter exceeds {max} entries. |
| `analysis.having.undeclared` | This dataset does not offer filtering the result. |
| `analysis.having.unknown-metric` | The filter refers to {metric}, which is not a usable metric. |
| `analysis.label.blank` | The display name is empty. |
| `analysis.limit.out-of-range` | Top N groups must be a whole number from 1 to {max}. |
| `analysis.metric.currency-unchecked` | {metric} is money in each record’s own currency, which the source cannot check, so its totals may add several currencies; group by {field} to see each currency. |
| `analysis.metric.filter-field-unsupported` | The source cannot use {field} in a metric's own condition. |
| `analysis.metric.out-of-reach` | The span of {metric} lies outside the dates picked, so it does not apply and is left empty. |
| `analysis.metric.type-unknown` | The metric type {type} is not available. |
| `analysis.metricFilter.empty` | This filter has no conditions, so the metric covers every record. |
| `analysis.metricFilter.incomplete` | Give {field} a value, or the metric covers every record. |
| `analysis.metricFilter.not-scalar` | {field} has no single value for a metric filter to test. |
| `analysis.metrics.empty` | An analysis needs at least one metric. |
| `analysis.metrics.too-many` | Too many metrics for this dataset. |
| `analysis.percentile.out-of-range` | A percentile must be between 0 and 100, exclusive. |
| `analysis.percentile.undeclared` | {field} does not offer percentiles. |
| `analysis.result.at-limit` | Showing the first {limit} groups; there may be more. |
| `analysis.result.mixed-currency` | {metric}: some groups hold records in several currencies, so their amounts are not added up; group by {field} to see each currency. |
| `analysis.result.more-groups` | Showing the first {limit} groups; there are more. |
| `analysis.sort.duplicate` | The sort already orders by {alias}. |
| `analysis.sort.metric-unsupported` | The source orders groups by their dimensions only, not by {alias}. |
| `analysis.sort.requires-group` | Sorting needs at least one dimension. |
| `analysis.sort.too-many` | A result sorts by at most {max} dimensions and metrics. |
| `analysis.sort.unknown-alias` | The sort orders by {alias}, which this result does not have. |
| `analysis.split.whole-failed` | Could not fold the smaller series into “Other”, so colours repeat: {reason} |

### `capability.*` {#issues-capability}

| Code | Default wording |
|---|---|
| `capability.analysis.count` | The source does not count records. |
| `capability.analysis.dense` | The source fills no gaps in a time dimension. |
| `capability.analysis.element-unavailable` | The source cannot aggregate over the elements of {path}. |
| `capability.analysis.expressions` | The source takes no formulas or derived metrics. |
| `capability.analysis.field-narrowed` | The source does not offer {dropped} on {field}. |
| `capability.analysis.field-unavailable` | The source cannot aggregate {field}. |
| `capability.analysis.having` | The source keeps no groups by a metric. |
| `capability.analysis.metric-sort` | The source orders groups by their dimensions only. |
| `capability.analysis.unavailable` | The source can aggregate none of what the analysis declares. |
| `capability.descriptor.disagrees` | {source} refused a query ({code}) its capability descriptor {version} admits: the service and its descriptor disagree. |
| `capability.descriptor.unavailable` | The capability descriptor of {source} could not be read; its views run on the definition alone. |
| `capability.field.alias` | The definition names {path} by its alias {field}: it is read as {path}. |
| `capability.field.deprecated` | The source deprecates {field}. |
| `capability.field.deprecated-because` | The source deprecates {field}: {reason} |
| `capability.field.not-projectable` | The source does not return {field}: its column reads empty. |
| `capability.field.operators-narrowed` | The source does not admit {operators} on {field}. |
| `capability.field.options-undescribed` | {field} lists {values}, which the source does not declare. |
| `capability.field.protected` | {field} is protected: shown, never filtered, sorted or searched by. |
| `capability.field.summary-narrowed` | The source cannot take the {summaries} summary of {field}. |
| `capability.field.temporal-mismatch` | {field} is declared as {declared}, but the source keeps it as {described}: its date conditions would be written wrong. |
| `capability.field.unfilterable` | The source admits no condition on {field}. |
| `capability.field.unknown` | The source does not list {field}: it is shown, but no condition, sort or summary uses it. |
| `capability.field.unsortable` | The source does not sort by {field}. |
| `capability.record.cursor-appended` | The source ends a cursor on {appended}, not on the row key {field}. |
| `capability.record.paging` | The record view pages by {paging}, which the source does not offer. |
| `capability.record.row-key-unsortable` | The source cannot sort by the row key {field}. |
| `capability.search.as-terms` | The source matches no phrase, so {field} searches by words. |
| `capability.search.fields-narrowed` | {field} cannot search {fields} on this source. |
| `capability.search.unavailable` | The source offers no search {field} can use. |

### `chart.*` {#issues-chart}

| Code | Default wording |
|---|---|
| `chart.as-table` | The chosen chart cannot draw this result; it shows as a table. |
| `chart.axis.scale-unknown` | This axis scale is not available. |
| `chart.boxplot.not-five-numbers` | A boxplot needs one field’s minimum, three rising percentiles and maximum, under one condition. |
| `chart.calendar.needs-day` | A calendar lays out days: its dimension must be a date by day. |
| `chart.candlestick.needs-date` | A candlestick runs along a date dimension. |
| `chart.candlestick.not-ohlc` | A candlestick needs one field’s opening value, maximum, minimum and closing value, under one condition. |
| `chart.cartesian.percent-not-additive` | A 100% stack needs metrics that add up (a record count or a sum), not {metric}. |
| `chart.colors.invalid` | This is not a colour the chart can paint with. |
| `chart.colors.malformed` | The pinned chart colours must be listed one per series or category. |
| `chart.combo.series-type-missing` | Every series of a combo chart needs its own type. |
| `chart.derived.kind-unknown` | This computed line is not available. |
| `chart.derived.metric-not-drawn` | The computed line follows {metric}, which the chart does not draw. |
| `chart.derived.window` | A moving average runs over 2 to {max} periods. |
| `chart.family.missing` | The {type} chart has no {family} settings yet. |
| `chart.funnel.duplicate-stage` | A funnel stage is listed twice. |
| `chart.funnel.metrics-need-no-group` | A funnel staged by metrics can carry no dimension. |
| `chart.funnel.not-additive` | A funnel needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.funnel.stages-need-category` | A funnel’s stages are the values of a category, not dates or number ranges. |
| `chart.funnel.too-few-stages` | A funnel needs at least two stages. |
| `chart.gauge.empty-scale` | The scale must end above where it starts. |
| `chart.gauge.needs-no-group` | A gauge can carry no dimension. |
| `chart.gauge.not-a-number` | This needs a number. |
| `chart.group.unconsumed` | The chart does not use the dimension {alias}. |
| `chart.group.unknown` | The chart uses a dimension this analysis does not have. |
| `chart.heatmap.same-axes` | A heatmap needs two different axes. |
| `chart.map.name-invalid` | The map must be named. |
| `chart.map.needs-region` | A map’s regions are the values of a dimension, not ranges or dates. |
| `chart.metric.moment` | {alias} is a point in time; a chart does not draw or compare it. |
| `chart.metric.needs-no-group` | A metric card can carry no dimension. |
| `chart.metric.trend-alias-mismatch` | The trend must use the {alias} dimension. |
| `chart.metric.trend-needs-one-date-group` | A trend needs exactly one time dimension. |
| `chart.metric.trend-not-additive` | A trend headline needs a metric that adds up, or one calculated from such metrics — not {metric}. |
| `chart.metric.unknown` | The chart uses a metric this analysis does not have. |
| `chart.parallel.duplicate-metric` | Parallel coordinates list one metric twice. |
| `chart.parallel.too-few-metrics` | Parallel coordinates need three metrics or more. |
| `chart.pie.maxSlices-too-small` | Keep at least two slices. |
| `chart.pie.not-additive` | A pie’s slices are shares of a whole, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.radar.duplicate-metric` | A radar lists one metric twice. |
| `chart.radar.too-few-metrics` | A radar needs three metrics or more. |
| `chart.referenceBand.order` | A target band runs from a smaller number to a larger one. |
| `chart.referenceLine.empty-axis` | A reference line needs a series on its axis. |
| `chart.referenceLine.metric-not-drawn` | The reference line is taken of {metric}, which is not drawn on its axis. |
| `chart.referenceLine.statistic-unknown` | This statistic is not available. |
| `chart.referenceLine.value-missing` | A reference line stands at a number or at a statistic. |
| `chart.sankey.not-additive` | A sankey’s bands add up to what flows through, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.sankey.same-levels` | A sankey lists one dimension twice. |
| `chart.sankey.too-few-levels` | A sankey needs two dimensions or more. |
| `chart.sankey.too-many-levels` | A sankey draws at most four dimensions. |
| `chart.scatter.same-metrics` | A scatter plot needs two different metrics. |
| `chart.splitBy.needs-one-series` | A split chart shows exactly one metric. |
| `chart.splitBy.same-as-x` | The split cannot repeat the horizontal axis. |
| `chart.sunburst.not-additive` | A sunburst’s parts add up to their parent, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.sunburst.same-levels` | A sunburst lists one dimension twice. |
| `chart.sunburst.too-few-levels` | A sunburst needs two dimensions or more. |
| `chart.sunburst.too-many-levels` | A sunburst draws at most four dimensions. |
| `chart.themeRiver.needs-date` | A theme river runs along a date dimension. |
| `chart.themeRiver.not-additive` | A theme river stacks its streams, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.themeRiver.same-axes` | A theme river needs two different dimensions. |
| `chart.tree.not-additive` | A tree’s parts add up to their parent, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.tree.same-levels` | A tree lists one dimension twice. |
| `chart.tree.too-few-levels` | A tree needs two dimensions or more. |
| `chart.tree.too-many-levels` | A tree draws at most four dimensions. |
| `chart.treemap.not-additive` | A treemap’s tiles are parts of a whole, so it needs a metric that adds up (a record count or a sum), not {metric}. |
| `chart.treemap.same-levels` | A treemap needs two different levels. |
| `chart.type.unknown` | This chart type is not available. |
| `chart.waterfall.not-additive` | A waterfall adds up its steps, so it needs a metric that adds up (a record count or a sum), not {metric}. |

### `config.*` {#issues-config}

| Code | Default wording |
|---|---|
| `config.filter.invalid` | The conditions of this view could not be read. |
| `config.filterMode.not-simple` | These conditions need the advanced editor to be shown in full. |
| `config.filterMode.unknown` | This view has an unknown filter mode. |
| `config.invalid` | This view could not be read. |
| `config.refresh.missing` | This view has no refresh setting. |
| `config.refresh.not-an-integer` | The refresh interval must be whole seconds. |
| `config.refresh.too-long` | The refresh interval cannot exceed {max} seconds. |
| `config.refresh.too-short` | The refresh interval must be at least {min} seconds. |

### `dashboard.*` {#issues-dashboard}

| Code | Default wording |
|---|---|
| `dashboard.binding.global-duplicate` | {field} is mapped twice on this panel. |
| `dashboard.binding.global-unknown` | {field} is not a filter field of this dashboard. |
| `dashboard.binding.kind-mismatch` | A {global} field cannot filter a {panel} field. |
| `dashboard.binding.missing` | This panel does not carry the {field} filter, so it cannot be filtered with the others. |
| `dashboard.binding.panel-unknown` | “{filter}” is wired to a field the view this panel shows does not have. |
| `dashboard.click.board-dimension-unknown` | The click carries {field} to another dashboard, which this panel no longer groups by; pressing it opens the follow-up menu. |
| `dashboard.click.board-filter-mismatch` | “{filter}” on the dashboard a press opens cannot take {field}; pressing it opens the follow-up menu. |
| `dashboard.click.board-filter-unknown` | The dashboard a press opens no longer has the filter “{filter}”; pressing it opens the follow-up menu. |
| `dashboard.click.board-gone` | The dashboard a press opens was deleted, or you may not open it; pressing it opens the follow-up menu. |
| `dashboard.click.board-not-a-board` | What the click opens is not a dashboard; pressing it opens the follow-up menu. |
| `dashboard.click.board-source-unknown` | The click carries this dashboard’s filter “{filter}” to another dashboard, but this dashboard no longer has it; pressing it opens the follow-up menu. |
| `dashboard.click.destination-unavailable` | The view a press goes to was deleted, or you may not open it. |
| `dashboard.click.destination-unsupported` | A view destination is a record or an analysis view; for a dashboard, choose “Dashboard”. |
| `dashboard.click.filter-ungrouped` | This panel does not group by {field}, which “{filter}” is wired to, so a press has no value for it; pressing it opens the follow-up menu. |
| `dashboard.click.filter-unknown` | The filter this panel’s click sets is no longer on the dashboard; pressing it opens the follow-up menu. |
| `dashboard.click.filter-unwired` | “{filter}” is not wired to this panel, so a press cannot set it; pressing it opens the follow-up menu. |
| `dashboard.click.invalid` | This panel’s click setting cannot be read; pressing it opens the follow-up menu. |
| `dashboard.click.unpressable` | This panel has no groups to press — a record view, or an analysis over expanded elements — so its click setting does nothing. |
| `dashboard.click.url-unknown-field` | The click’s address names {field}, which this panel does not group by; pressing it opens the follow-up menu. |
| `dashboard.click.url-unsafe` | The click’s address is not http, https, mailto or inside this application; pressing it opens the follow-up menu. |
| `dashboard.click.view-not-a-view` | What a press goes to is not a record or an analysis view; pressing it opens the follow-up menu. |
| `dashboard.click.view-undeclared` | “{definition}” has no view like the one a press goes to; pressing it opens the follow-up menu. |
| `dashboard.click.view-unknown` | The view a press goes to cannot be found in this application; pressing it opens the follow-up menu. |
| `dashboard.field.duplicate` | The filter field {field} is declared twice. |
| `dashboard.field.name-empty` | A filter field needs a name. |
| `dashboard.field.name-invalid` | {field} is not a usable field name. |
| `dashboard.field.not-multiple` | The filter {field} takes one value, and this holds several. |
| `dashboard.field.not-one-day` | The filter {field} holds one day: a day off the calendar, or today, yesterday or the day before. |
| `dashboard.field.one-day-not-date` | The filter {field} is not a date filter, so it cannot be held to one day. |
| `dashboard.field.options-empty` | The filter {field} picks from a list of its own, and the list is empty. |
| `dashboard.field.required-no-default` | The filter {field} always has a value, so it needs a default to start at. |
| `dashboard.fields.too-many` | A dashboard holds at most {max} filters. |
| `dashboard.filter.held` | The page holds the filter {field}; it cannot be changed here. |
| `dashboard.filter.unknown` | This dashboard has no filter {field}. |
| `dashboard.grid.unsupported` | This dashboard is laid out on a grid that cannot be drawn here; panels are placed on {columns} columns. |
| `dashboard.grouping.kept` | This panel keeps its own time grouping: its data cannot be grouped the way the dashboard is. |
| `dashboard.grouping.unit-duplicate` | The time grouping offers {unit} twice. |
| `dashboard.grouping.unit-unknown` | {unit} is not a time grouping this dashboard offers. |
| `dashboard.grouping.units-empty` | The time grouping offers nothing to choose from. |
| `dashboard.heading.too-long` | A heading holds at most {max} characters. |
| `dashboard.layout.invalid` | This panel has an unusable position or size. |
| `dashboard.layout.missing` | This panel has no position. |
| `dashboard.layout.out-of-grid` | This panel reaches past the {columns} columns of the grid. |
| `dashboard.link.label-empty` | A link needs a label. |
| `dashboard.links.too-many` | A links panel holds at most {max} links. |
| `dashboard.markdown.too-long` | A text panel holds at most {max} characters. |
| `dashboard.panel.definition-unknown` | The data the analysis in this panel was built on is no longer available. |
| `dashboard.panel.failed` | The view this panel shows could not be opened. |
| `dashboard.panel.id-duplicate` | Two panels share the id {id}. |
| `dashboard.panel.id-empty` | A panel needs an id. |
| `dashboard.panel.kind-unsupported` | This panel points at something that is not a record or analysis view. |
| `dashboard.panel.not-owned` | Only an analysis that lives in this dashboard can be saved as a view. |
| `dashboard.panel.not-referenced` | This panel does not show a saved view you have open, so there is nothing to copy. |
| `dashboard.panel.opens-elsewhere` | The view this panel was set to open in the workbench, “{view}”, is over “{definition}”, not this panel's data, so it opens the panel's own view. |
| `dashboard.panel.opens-invalid` | The view this panel was set to open in the workbench is not named properly, so it opens the panel's own view. |
| `dashboard.panel.opens-undeclared` | “{definition}” has no view like the one this panel was set to open in the workbench, so it opens the panel's own view. |
| `dashboard.panel.opens-unknown` | The view this panel was set to open in the workbench cannot be found in this application, so it opens the panel's own view. |
| `dashboard.panel.owned-invalid` | The analysis this panel holds is not in the expected shape. |
| `dashboard.panel.presentation-dropped` | How this panel was set to look no longer fits its view, so it shows the view's own look. |
| `dashboard.panel.scope-too-narrow` | The view this panel shows is not open to everyone who reads this dashboard. |
| `dashboard.panel.source-invalid` | This panel should show either a saved view or an analysis of its own. |
| `dashboard.panel.tab-unknown` | This panel is on no tab of this dashboard, so it is shown on the first. |
| `dashboard.panel.unavailable` | The view this panel shows was deleted, or you do not have access to it. |
| `dashboard.panel.unknown-kind` | This type of panel is not available. |
| `dashboard.panel.view-undeclared` | “{definition}” has no view like the one this panel shows. |
| `dashboard.panel.view-unknown` | The view this panel shows cannot be found in this application. |
| `dashboard.panels.too-many` | A dashboard holds at most {max} panels. |
| `dashboard.scope.unsupported` | A dashboard takes no outer condition: lock or hide its filters instead. |
| `dashboard.shape.invalid` | This part of the dashboard is not in the expected shape. |
| `dashboard.system.non-system-panels` | These panels reference views that are not system views; publish those as system views first: {panels} |
| `dashboard.tab.id-duplicate` | Two tabs share the id {id}. |
| `dashboard.tab.id-empty` | A tab needs an id. |
| `dashboard.tab.title-empty` | A tab has no name, so it is called by its place. |
| `dashboard.tabs.too-many` | A dashboard holds at most {max} tabs. |
| `dashboard.url.unsupported-scheme` | Only http, https, mailto and relative links can be shown. |
| `dashboard.width.unknown` | This dashboard’s width “{width}” is not one this release knows, so it is shown full width. Choose fixed width or full width while editing. |

### `definition.*` {#issues-definition}

| Code | Default wording |
|---|---|
| `definition.analysis.date-part-not-temporal` | {field} offers grouping by weekday or hour, but holds no date or time. |
| `definition.analysis.date-part-unknown` | {field} declares an unknown calendar part: {value}. |
| `definition.analysis.default-limit-too-large` | The default row limit exceeds the maximum. |
| `definition.analysis.element-field-unknown` | {path} declares no field named {field}. |
| `definition.analysis.element-undeclared` | The analysis expands {path}, which is not a field holding elements. |
| `definition.analysis.elements-too-many` | The analysis declares more than {max} nested levels. |
| `definition.analysis.field-unknown` | The analysis capability names {field}, which the definition does not declare. |
| `definition.analysis.limit-invalid` | An analysis limit must be a positive whole number. |
| `definition.analysis.no-metric` | The analysis capability offers no metric to start from. |
| `definition.analysis.wider` | The source does not offer {what} to analyses. |
| `definition.descriptor.missing` | No descriptor was given for the source {source}, so its capabilities go unchecked. |
| `definition.field.analysis-wider` | {field} does not offer {what} to analyses at its source. |
| `definition.field.cell-invalid` | {field} declares an unknown cell renderer: {value}. |
| `definition.field.deprecated` | {field} is deprecated by its source ({message}); say why it is kept. |
| `definition.field.duplicate` | The field {field} is declared twice. |
| `definition.field.editor-removed` | {field} declares an editor, which no longer exists; delete the member. |
| `definition.field.element-search-fields-required` | {field} searches inside an entry, so it has to name the entry fields it looks in. |
| `definition.field.element-title-not-a-value` | {field} titles its elements by {title}, which holds no value of its own to read. |
| `definition.field.element-title-unknown` | {field} titles its elements by {title}, which its elements do not declare. |
| `definition.field.kind-unknown` | The descriptor does not say what kind of value {field} holds; give it a kind. |
| `definition.field.kind-unregistered` | {field} uses the unregistered type {kind}. |
| `definition.field.name-invalid` | {field} is not a usable field name. |
| `definition.field.not-elements` | {field} is not an array whose entries the source lists. |
| `definition.field.numeric-invalid` | {field} declares a number format the engine cannot write: {value}. |
| `definition.field.operator-wider` | {field} does not take {operator} at its source. |
| `definition.field.search-fields-unknown` | {field} searches {missing}, which the definition does not declare. |
| `definition.field.search-mode-invalid` | {field} declares an unknown search mode: {value}. |
| `definition.field.sort-wider` | {field} does not sort at its source. |
| `definition.field.string-comparison-invalid` | {field} declares an unknown text comparison: {value}. |
| `definition.field.summary-wider` | {field} cannot be summarised by {fn} at its source. |
| `definition.field.temporal-invalid` | {field} declares a time storage the engine cannot write: {value}. |
| `definition.field.temporal-misplaced` | {field} declares a time storage, but its type {kind} writes no time. |
| `definition.field.time-precision-invalid` | {field} declares an unknown time precision: {value}. |
| `definition.field.time-precision-misplaced` | {field} declares a time precision, but reads as {cell}, which has no time of day. |
| `definition.field.tone-invalid` | {field} declares an unknown option tone: {value}. |
| `definition.field.undescribed` | {field} is not a path the source’s descriptor lists. |
| `definition.field.unlabelled` | {field} has no label of its own, so it is shown by its description or its path. |
| `definition.fieldGroup.duplicate` | The group {group} is declared twice. |
| `definition.fieldGroup.field-duplicate` | The group {group} lists {field}, which is already listed under another group. |
| `definition.fieldGroup.field-unknown` | The group {group} lists {field}, which the definition does not declare. |
| `definition.fieldGroup.invalid` | A field group needs an id and a label. |
| `definition.id.separator` | A definition id cannot contain {separator}. |
| `definition.option.undescribed` | {value} is not one of the values the source lists for {field}. |
| `definition.record.layouts-empty` | The record capability offers no layout. |
| `definition.record.max-sort-fields-invalid` | The sort bound must be a whole number of fields, not {value}. |
| `definition.record.max-window-cursor` | A paging window bounds pages, and this source pages by cursor. |
| `definition.record.max-window-invalid` | The paging window must be a whole number of rows above zero, not {value}. |
| `definition.record.paging-wider` | The source does not page by {paging}. |
| `definition.record.row-field-not-a-path` | The row field {field} is a search or metadata handle, not a value a row holds. |
| `definition.record.row-field-unknown` | The row field {field} is not a declared field. |
| `definition.record.row-key-unknown` | The row key {field} is not a declared field. |
| `definition.record.row-key-unsortable` | The row key {field} must be sortable: every page is ordered by it last, so a row never shows on two pages. |
| `definition.source.unregistered` | No source is registered for {source}: register this definition with its source. |
| `definition.text.fallback` | The words in force give none for the key {key}; the engine’s starting words say it instead. |
| `definition.text.unknown` | No words are given for the key {key}. |
| `definition.timeField.not-time` | The time field {field} is not a moment. |
| `definition.timeField.unknown` | The time field {field} is not a field of the definition. |
| `definition.view.analysis-open` | The analyses this view needs come from its source: its snapshot offers none, so it opens only where the source grants some. |
| `definition.view.id-duplicate` | Two views share the id {id}. |
| `definition.view.id-separator` | A view id cannot contain {separator}. |
| `definition.view.kind-mismatch` | A {kind} view does not belong to a {definition} definition. |
| `definition.view.owned-invalid` | The analysis panel {panel} holds is incomplete or set up wrong. |

### `export.*` {#issues-export}

| Code | Default wording |
|---|---|
| `export.failed` | The export failed. {reason} |

### `filter.*` {#issues-filter}

| Code | Default wording |
|---|---|
| `filter.element.duration` | A time since another moment cannot be asked of one entry. |
| `filter.element.root-filter` | {field} asks about the whole record, so it cannot be asked of one entry. |
| `filter.field.duplicate-in-group` | {field} is already a condition in this group. Nest a group to ask it something else. |
| `filter.field.holds-no-elements` | {field} holds no entries to match against. |
| `filter.field.reference-without-source` | {field} is a reference field with no candidate source declared. |
| `filter.field.unknown` | The field {field} no longer exists. |
| `filter.group.unknown-operator` | A condition group must be AND or OR. |
| `filter.kind.failed` | The condition on "{label}" couldn't be checked; ask whoever maintains this view. |
| `filter.kind.unknown-editor` | The {kind} type asks for a {input} editor, which this engine does not have. |
| `filter.kind.unregistered` | No editor is registered for the {kind} type. |
| `filter.node.invalid` | This condition could not be read. |
| `filter.operator.unsupported` | {field} does not support {operator}. |
| `filter.presence.empty-is-missing` | On this source, an empty value of {field} counts as no value. |
| `filter.tree.too-deep` | The conditions nest deeper than {max} levels. |
| `filter.tree.too-many-nodes` | The conditions exceed {max} entries. |
| `filter.value.duration-from-not-time` | {field} holds no time. |
| `filter.value.duration-from-unknown` | The earlier time {field} is not a field here. |
| `filter.value.duration-same-time` | A time cannot be measured since itself. |
| `filter.value.expected-boolean` | Choose yes or no. |
| `filter.value.expected-date` | Enter a date. |
| `filter.value.expected-deletion-state` | Choose which records to show: not deleted, deleted only, or both. |
| `filter.value.expected-duration` | A time since needs an earlier time, a comparison, an amount and a unit. |
| `filter.value.expected-entry-list` | Choose or enter one or more entries. |
| `filter.value.expected-id` | Enter an id, or pick a candidate. |
| `filter.value.expected-id-list` | Enter one or more ids. |
| `filter.value.expected-number` | Enter a number. |
| `filter.value.expected-number-list` | Enter one or more numbers. |
| `filter.value.expected-number-range` | Enter a range of two numbers. |
| `filter.value.expected-option-list` | Choose one or more options. |
| `filter.value.expected-predicate` | Describe what an entry must match. |
| `filter.value.expected-reference-list` | Choose one or more records. |
| `filter.value.expected-string` | Enter a value. |
| `filter.value.expected-string-list` | Enter one or more values. |
| `filter.value.expected-text` | Type what to search for. |
| `filter.value.expects-one` | This condition takes a single value. |
| `filter.value.inverted-range` | The range starts after it ends. |
| `filter.value.relative-too-large` | A relative window can reach at most {max} units. |
| `filter.value.required` | This condition needs a value. |
| `filter.value.unknown-option` | {values} is no longer an option. |
| `filter.value.unknown-time-zone` | {timeZone} is not a time zone this browser knows. |
| `filter.value.unparsable-date` | That date cannot be read. |

### `record.*` {#issues-record}

| Code | Default wording |
|---|---|
| `record.capability.missing` | {definition} does not offer a record view any more. |
| `record.card.invalid` | The card settings could not be read. |
| `record.column.duplicate` | The column {field} is listed twice. |
| `record.column.hidden-invalid` | The column {field} is switched off as {hidden}, which is not how a column is switched off. |
| `record.column.pin-invalid` | The column {field} is pinned as {pinned}, which is not how a column is pinned. |
| `record.column.width-invalid` | The column {field} is {width} wide, which is not a number of pixels. |
| `record.detail.failed` | The whole record could not be read: {reason} |
| `record.detail.forbidden` | You do not have permission to read this record. |
| `record.field.not-a-column` | {field} is a search or metadata handle, not something a row holds. |
| `record.field.unknown` | The column {field} no longer exists. |
| `record.filter.required` | This data source lists records only under a condition: add one first. |
| `record.layout.unsupported` | The {layout} layout is not available here. |
| `record.pageSize.not-positive` | The page size must be a positive number. |
| `record.pageSize.too-large` | The page size cannot exceed {max}. |
| `record.sort.direction-invalid` | The sort on {field} reads neither ascending nor descending. |
| `record.sort.duplicate` | The sort already orders by {field}. |
| `record.sort.invalid` | The sort settings could not be read. |
| `record.sort.not-sortable` | {field} cannot be sorted on. |
| `record.sort.parallel-arrays` | The source cannot sort by {fields} together: keep one of them. |
| `record.sort.too-many` | A cursor view sorts on at most {max} fields. |
| `record.summaries.invalid` | The summary settings could not be read. |
| `record.summary.duplicate` | The {fn} summary of {field} is listed twice. |
| `record.summary.unsupported` | {field} does not offer the {fn} summary. |
| `record.table.invalid` | The table settings could not be read. |

### `runtime.*` {#issues-runtime}

| Code | Default wording |
|---|---|
| `runtime.kind.not-declared` | {definition} does not offer a {kind} view. |
| `runtime.options.unresolved` | No candidate source is configured for {source}. |
| `runtime.query.failed` | Could not load the data: {reason} |
| `runtime.query.failed.any_requires_single_value` | “Any value” needs a field that holds a single value. |
| `runtime.query.failed.array_equality` | {field} cannot be compared with a whole list here; match its items instead. |
| `runtime.query.failed.body_not_object` | The service could not read the query this view sent: {reason} |
| `runtime.query.failed.count_requires_filter` | Add a condition first: the service will not count every record. |
| `runtime.query.failed.cursor_not_allowed` | {field} cannot be used to page through this data. |
| `runtime.query.failed.cursor_sort_duplicate` | The sort names {field} twice; keep it once. |
| `runtime.query.failed.cursor_sort_too_many` | The sort has too many fields to page through this data; remove some. |
| `runtime.query.failed.element_scope_required` | {field} can only be filtered inside its list, with a condition on the list's elements. |
| `runtime.query.failed.empty_body` | The service received an empty query: {reason} |
| `runtime.query.failed.event_projection_type_required` | An event's payload cannot be shown without its type; keep the event type column. |
| `runtime.query.failed.expensive_operator_disabled` | The service does not allow this costly query here: {reason} |
| `runtime.query.failed.explicit_entry_required` | The query named no entry to run against: {reason} |
| `runtime.query.failed.filter_too_large` | The conditions are more than the service accepts in one query: {reason} |
| `runtime.query.failed.first_last_requires_order_by` | “First value” and “Last value” of {field} need a field to order by here: this data has no event time in that place. |
| `runtime.query.failed.first_last_requires_single_value` | “First value” and “Last value” need a field that holds a single value; {field} may hold several. |
| `runtime.query.failed.identity_undefined` | This data defines no record identity, so it cannot be paged through or picked by id. |
| `runtime.query.failed.incomplete_projection` | The service cannot return whole records here; choose the columns to show. |
| `runtime.query.failed.invalid_cursor` | This page can no longer be continued; start again from the first page. |
| `runtime.query.failed.invalid_json` | The service could not read the query this view sent: {reason} |
| `runtime.query.failed.invalid_request` | The service refused the query: {reason} |
| `runtime.query.failed.invalid_value` | A value in this query is missing or of the wrong kind: {reason} |
| `runtime.query.failed.metric_filter_array_field` | A metric's own condition cannot use the list field {field}. |
| `runtime.query.failed.metric_filter_element_match` | A metric's own condition cannot match list elements. |
| `runtime.query.failed.metric_filter_search` | A metric's own condition cannot use search. |
| `runtime.query.failed.missing_key_requires_string` | Only a single text field can group its missing values; {field} is not one. |
| `runtime.query.failed.model_search_unsupported` | This data does not support search. |
| `runtime.query.failed.not_collection` | {field} is not a list, so it cannot be checked for being empty. |
| `runtime.query.failed.not_projectable` | {field} cannot be shown as a column of this data. |
| `runtime.query.failed.not_single_string` | {field} is not a single text value, so it cannot be checked for being empty. |
| `runtime.query.failed.parallel_array_sort` | {field} and another list field in the sort cannot be sorted by together; keep one of them. |
| `runtime.query.failed.protected_aggregation` | {field} is protected and cannot be summarised. |
| `runtime.query.failed.protected_comparison` | {field} is protected and cannot be used to filter, sort or search. |
| `runtime.query.failed.residual_groups_exceeded` | This summary has too many groups for the service to filter or sort by a metric; narrow the data first. |
| `runtime.query.failed.size_out_of_range` | The service limits how many rows one request may read: {reason} |
| `runtime.query.failed.sort_field_duplicate` | {field} is sorted by twice; keep it once. |
| `runtime.query.failed.sort_too_many` | The sort has too many fields; remove some. |
| `runtime.query.failed.storage_unsupported` | The service's storage cannot run this: {reason} |
| `runtime.query.failed.temporal_aggregation_unsupported` | {field} does not store its time as a date or a timestamp, so it cannot be grouped or measured by time. |
| `runtime.query.failed.temporal_configuration_conflict` | The relative time on {field} does not agree with how the field keeps its time: {reason} |
| `runtime.query.failed.temporal_representation_required` | {field} is not stored as a known date or time, so a relative time cannot be asked of it. |
| `runtime.query.failed.unknown_field` | The service does not know the field {field}. The view may be ahead of the data; remove the condition, column or group on it. |
| `runtime.query.failed.unknown_property` | The service does not understand part of this query; it may be older than this view engine: {reason} |
| `runtime.query.failed.unknown_type` | The service does not know an operator or metric this view uses; it may be older than this view engine: {reason} |
| `runtime.query.failed.unknown_value` | The service does not know a value this view sent: {reason} |
| `runtime.query.failed.unsupported_capability` | {field} cannot be used this way on this data: {reason} |
| `runtime.query.failed.value_mismatch` | A value is not one {field} can hold. |
| `runtime.query.forbidden` | You do not have permission to view this data. |
| `runtime.query.queue-full` | Too many queries at once; try again in a moment. |
| `runtime.query.too-many-nodes` | These conditions make a query of {count} parts; the source takes at most {max}. Remove some conditions. |
| `runtime.query.too-many-values` | One condition lists {count} values; the source takes at most {max} in one condition. |
| `runtime.query.unreachable` | Could not load the data: the server could not be reached. |
| `runtime.source.unresolved` | No source is registered for {source}. |
| `runtime.summary.page-only` | The totals query failed, so the summary adds up only the rows on this page. |

### `view.*` {#issues-view}

| Code | Default wording |
|---|---|
| `view.abandon.failed` | That write could not be set aside. |
| `view.change.notify-failed` | A listener on view changes failed: {reason}. The list may be a revision behind. |
| `view.changeAudience.failed` | Who this view is for could not be changed. |
| `view.changeAudience.forbidden` | You may not change who this view is for; save a copy of your own instead. |
| `view.changeAudience.invalid` | Who this view is for could not be changed: {reason} |
| `view.changeAudience.invalid.shared-boards` | This view stays shared: shared dashboards show it ({boards}). |
| `view.changeAudience.unsupported` | This view store cannot move a view between personal and shared. |
| `view.config.invalid` | Fix what this view reports before saving it. |
| `view.config.too-large` | This view is too large to save: {size} KB, where the store keeps at most {max} KB. |
| `view.create.forbidden` | You may not create views here. |
| `view.definition.invalid` | The {id} definition has {issues} problem(s) and cannot be opened. |
| `view.definition.not-found` | No definition named {id}. |
| `view.delete.failed` | This view could not be deleted. |
| `view.delete.forbidden` | You may not delete this view; ask whoever owns it to remove it. |
| `view.field.deprecated` | This view uses {field}, which its data source deprecates. |
| `view.field.deprecated-because` | This view uses {field}, which its data source deprecates: {reason} |
| `view.list.failed` | The list of views could not be loaded. |
| `view.list.failed.unavailable` | The list of views could not be loaded: the server could not be reached. |
| `view.list.failed.unavailable.server` | The list of views could not be loaded: the server could not handle it ({reason}). |
| `view.list.failed.unsupported` | The list of views could not be loaded: this server has no view store. |
| `view.list.reserved-id` | The stored view {id} uses a reserved id and was skipped. |
| `view.open.failed` | This view could not be opened. |
| `view.open.failed.forbidden` | You do not have access to this view. Ask an administrator to grant it. |
| `view.open.failed.not_found` | This view no longer exists. |
| `view.open.failed.unavailable` | This view could not be loaded: the server could not be reached. |
| `view.open.failed.unavailable.server` | This view could not be loaded: the server could not handle it ({reason}). |
| `view.open.failed.unsupported` | This view could not be loaded: this server has no view store. |
| `view.open.not-found` | No view named {id}. |
| `view.open.other-definition` | This view belongs to another page, so this page cannot show it. |
| `view.open.wrong-kind` | This view is of another kind ({kind}), so this page cannot show it. |
| `view.preferences.default-forbidden` | You may not set the default view. |
| `view.preferences.failed` | Your view preferences could not be saved. |
| `view.preferences.load-failed` | Your view preferences could not be loaded; the views are in the server’s order. |
| `view.preferences.reorder-forbidden` | You may not reorder views. |
| `view.publish.failed` | This view could not be published as a system view. |
| `view.publish.forbidden` | You may not publish system views; ask an administrator. |
| `view.rename.failed` | This view could not be renamed. |
| `view.rename.forbidden` | You may not rename this view; save a copy of your own instead. |
| `view.resolve.failed` | That conflict could not be resolved. |
| `view.retry.failed` | That write could not be retried. |
| `view.runtime.not-owned` | This view is not open here any more. |
| `view.save-as.failed` | This view could not be saved as a copy. |
| `view.save.failed` | This view could not be saved. |
| `view.save.forbidden` | You may not save changes to this view; save a copy of your own instead. |
| `view.system.read-only` | A built-in view cannot be changed ({action}). |
| `view.title.empty` | A view needs a title. |
| `view.title.too-long` | A view’s title can be at most {max} characters. |
| `view.write.conflict` | Someone else saved this view first. |
| `view.write.conflict-unreadable` | Someone else saved first, and their version could not be read. |
| `view.write.forbidden` | You may not write to this view. |
| `view.write.in-flight` | This view is already being saved; wait for that to finish. |
| `view.write.invalid` | The server refused this write: {reason} |
| `view.write.not-a-conflict` | That write is {kind}, not a conflict. |
| `view.write.not-pending` | That write is already settled. |
| `view.write.not_found` | This view no longer exists. |
| `view.write.storage` | This browser could not keep the change: its storage is full or turned off. |
| `view.write.unavailable` | The server could not be reached. |
| `view.write.unavailable.server` | The server could not handle this write: {reason} |
| `view.write.unknown-pending` | The last {action} has not been confirmed yet. Retry it or abandon it before writing again. |
| `view.write.unsupported` | This server has no view store. |

:::

<!-- issue-codes:end -->

## The full working version

- The list and its test: [`test/surface/issues.txt`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/surface/issues.txt), [`test/fixtures/issueCodes.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/fixtures/issueCodes.ts).
- The catalogue: [`src/ui/messages/en.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/ui/messages/en.ts); `Issue` is [`src/model/issue.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/model/issue.ts).
