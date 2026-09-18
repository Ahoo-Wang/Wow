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

import type { Issue } from '../model/index.js';

/**
 * Wording, by key.
 *
 * The model carries `code` and `params` and no copy at all, which is what
 * lets an application translate or reword any of it. This is the other half
 * of that decision: without a catalogue the codes reach the screen, and
 * `record.summary.unsupported` is not a sentence anybody should read.
 *
 * Two namespaces share one flat map: an issue's `code`, and a `label.*` key
 * for the text a component writes itself. A missing key falls back to the key,
 * so a gap shows up as the code it always used to show rather than as nothing.
 */
export type ViewMessages = Readonly<Record<string, string>>;

/** `{field}` and friends are replaced from `Issue.params`. */
const PLACEHOLDER = /\{(\w+)\}/g;

export function formatMessage(
  messages: ViewMessages,
  key: string,
  params?: Issue['params'],
): string {
  const template = lookup(messages, key);
  if (template === undefined) return key;
  if (!params) return template;
  return template.replace(PLACEHOLDER, (whole, name: string) => {
    const value = params[name];
    return value === undefined ? whole : String(value);
  });
}

/**
 * The closest entry: the key itself, else the longest prefix that has one.
 *
 * `/react` composes a code from a command and what the store said, as in
 * `view.open.failed.not_found`, so the catalogue would otherwise need an
 * entry for every command crossed with every store code. Falling back along
 * the dots keeps it to the commands, and an application that wants to name
 * one of those combinations still can, by adding the longer key.
 */
function lookup(messages: ViewMessages, key: string): string | undefined {
  let candidate = key;
  for (;;) {
    const found = messages[candidate];
    if (found !== undefined) return found;
    const cut = candidate.lastIndexOf('.');
    if (cut < 0) return undefined;
    candidate = candidate.slice(0, cut);
  }
}

/** One issue as a sentence. */
export function formatIssue(messages: ViewMessages, issue: Issue): string {
  return formatMessage(messages, issue.code, issue.params);
}

/** Several issues, in order, joined for one alert. */
export function formatIssues(
  messages: ViewMessages,
  issues: readonly Issue[],
): string {
  return issues.map(found => formatIssue(messages, found)).join(' ');
}

/**
 * English wording for everything this package can report.
 *
 * `test/messages.test.ts` fails when a new issue code has no entry here, so
 * the catalogue cannot drift behind the kernels.
 */
export const defaultMessages: ViewMessages = Object.freeze({
  // Labels the default components write themselves.
  //
  // The save commands first. Conflict and delete carry the most weight of anything
  // this package says, so they are the last copy that should be stranded in
  // JSX where no application can reword or translate it.
  'label.save.save': 'Save',
  'label.save.save-as': 'Save as',
  'label.save.rename': 'Rename',
  'label.save.delete': 'Delete',
  'label.save.title': 'Title',
  'label.save.audience': 'Who can see it',
  'label.delete.confirm': 'Delete this view?',
  'label.delete.consequence':
    'It disappears for everyone who can see it. This cannot be undone.',
  'label.delete.keep': 'Keep it',
  'label.dialog.cancel': 'Cancel',
  'label.save-as.heading': 'Save as a new view',
  'label.save-as.description': 'The view you are looking at stays as it is.',
  'label.rename.heading': 'Rename this view',
  'label.rename.description': 'Only the title changes; the conditions stay.',
  'label.conflict.choice':
    'Take their version and lose your edits, or write yours over theirs.',
  'label.conflict.theirs': 'Take theirs',
  'label.conflict.mine': 'Keep mine',
  'label.unknown.consequence':
    'It may well have been saved. Retrying asks again for the same write rather than making a second one.',
  'label.unknown.leave': 'Leave it',
  'label.unknown.retry': 'Retry',

  // Layout and mode switches.
  'label.layout.table': 'Table',
  'label.layout.chart': 'Chart',
  'label.layout.cards': 'Cards',
  'label.filter.simple': 'Simple',
  'label.filter.advanced': 'Advanced',
  'label.filter.all-of': 'All of',
  'label.filter.any-of': 'Any of',
  'label.filter.none-of': 'None of',
  'label.filter.add-group': 'Add group',
  'label.filter.choose': 'Choose',
  'label.analysis.row-count': 'Row count',

  // The record toolbar. `label.toolbar.page` is the count-less form a cursor
  // view gets: a total it never asked for cannot be shown.
  'label.toolbar.layout': 'Layout',
  'label.toolbar.columns': 'Columns',
  'label.toolbar.refresh': 'Refresh',
  'label.toolbar.selected': '{count} selected',
  'label.toolbar.page': 'Page {index}',
  'label.toolbar.page-of': 'Page {index} of {pages}',
  'label.toolbar.previous': 'Previous page',
  'label.toolbar.next': 'Next page',

  // The record view itself.
  'label.record.empty': 'Nothing to show',
  'label.record.empty-hint': 'No record matches the current conditions.',
  'label.record.select-all': 'Select all rows',
  'label.record.select': 'Select {key}',
  'label.summary.of': '{fn} of {field}',
  'label.value.yes': 'Yes',
  'label.value.no': 'No',

  // The condition builder. The names ending in `-of` are accessible names,
  // which is why they read as a field followed by what the control does: a
  // screen reader announces them one after another and nothing else tells
  // the two `warehouse` selects apart.
  'label.filter.panel': 'Filter',
  'label.filter.mode': 'Filter mode',
  'label.filter.clear': 'Clear',
  'label.filter.apply': 'Apply',
  'label.filter.group-operator': 'Group operator',
  'label.filter.remove-group': 'Remove group',
  'label.filter.add-condition': 'Add condition',
  'label.filter.add-condition-here': 'Add condition in this group',
  'label.filter.operator-of': '{field} operator',
  'label.filter.value-of': '{field} value',
  'label.filter.remove-of': 'Remove {field}',
  'label.filter.comma-separated': 'Comma separated',
  'label.filter.range-from': '{field} from',
  'label.filter.range-to': '{field} to',
  'label.boolean.true': 'True',
  'label.boolean.false': 'False',

  // The three date shapes, the side of now a relative window lies on, and
  // the controls that edit them.
  'label.date.absolute': 'On a date',
  'label.date.relative': 'Relative',
  'label.date.preset': 'A period',
  'label.date.past': 'In the last',
  'label.date.future': 'In the next',
  'label.date.pick': 'Pick a date',
  'label.date.shape-of': '{field} kind',
  'label.date.direction-of': '{field} direction',
  'label.date.amount-of': '{field} amount',
  'label.date.unit-of': '{field} unit',
  'label.date.period-of': '{field} period',

  // Who a view is for. The audience picker asks it of the person saving,
  // the list's tooltip answers it about a view already saved, so the two
  // are worded differently and keyed apart.
  'label.scope.only-me': 'Only me',
  'label.scope.everyone': 'Everyone',
  'label.scope.system': 'Shipped with the definition',
  'label.scope.shared': 'Shared with everyone',
  'label.scope.personal': 'Only you',
  'label.scope.tag.system': 'system',
  'label.scope.tag.shared': 'shared',
  'label.scope.tag.personal': 'personal',
  'label.view.list': 'Views',
  'label.view.list-failed': 'The list could not be loaded.',
  'label.view.none-hint': 'Save the current conditions to make one.',

  // The analysis editor. Its "Add group" is its own: a grouping is not a
  // condition group, and a translation that shares one word for both is a
  // translation this package forced.
  'label.analysis.editor': 'Analysis',
  'label.analysis.layout': 'Analysis layout',
  'label.analysis.add-group': 'Add group',
  'label.analysis.add-metric': 'Add metric',
  'label.analysis.show-totals': 'Show totals',
  'label.analysis.totals': 'Totals',
  'label.analysis.row-limit': 'Row limit',
  'label.analysis.run': 'Run',
  'label.analysis.chart-type': 'Chart type',
  'label.analysis.grouping-of': '{alias} grouping',
  'label.analysis.remove-group': 'Remove group {alias}',
  'label.analysis.function-of': '{alias} function',
  'label.analysis.remove-metric': 'Remove metric {alias}',

  // Series names the chart writes itself, for legends and tooltips.
  'label.chart.other': 'Other',
  'label.chart.points': 'Points',
  'label.chart.trend': 'Trend',
  'label.chart.cell': '{y} · {x}: {value}',

  // Operator names a component would otherwise derive from the enum. Only the
  // ones whose derived spelling is unreadable need an entry; the rest fall
  // back, so this list stays short and an application can still name any of
  // them by adding a key.
  'label.operator.ID': 'is',
  'label.operator.IDS': 'is any of',
  'label.operator.AGGREGATE_ID': 'is',
  'label.operator.AGGREGATE_IDS': 'is any of',
  'label.operator.TENANT_ID': 'is',
  'label.operator.OWNER_ID': 'is',
  'label.operator.SPACE_ID': 'is',
  'label.operator.IS_EMPTY_STRING': 'is blank',
  'label.operator.IS_NOT_EMPTY_STRING': 'is not blank',
  'label.operator.IS_NULL': 'is empty',
  'label.operator.IS_NOT_NULL': 'is not empty',
  'label.operator.NOT_EXISTS': 'does not exist',
  'label.operator.IN': 'is any of',
  'label.operator.NOT_IN': 'is none of',
  'label.operator.CONTAINS_ALL': 'has all of',
  'label.operator.IS_EMPTY': 'has no entries',
  'label.operator.STARTS_WITH': 'starts with',
  'label.operator.ENDS_WITH': 'ends with',

  // A summary's scope belongs on screen: a total comes from its own query
  // over everything the conditions match, a page total only from the rows
  // in front of you, and the two are not interchangeable.
  'label.summary.total': 'Total',
  'label.summary.page': 'This page',
  'label.summary.unavailable': '—',
  'label.view.unopenable': 'This view could not be opened',
  'label.view.needs-fixing': 'This view needs fixing before it runs',
  'label.view.none': 'No view yet',
  'label.dashboard.needs-fixing': 'This dashboard needs fixing before it runs',
  'label.dashboard.empty': 'No panels yet',
  'label.dashboard.empty-hint':
    'Add a saved record or analysis view to see it here.',
  'label.panel.unavailable': 'This panel is unavailable',
  'label.panel.unavailable-hint': 'The view it shows could not be opened.',
  'label.query.failed': 'The query failed',
  'label.scope.refused': 'This page could not narrow this view',
  'label.analysis.empty': 'Nothing to aggregate',
  'label.write.conflict': 'Someone else saved this view first',
  'label.write.unknown': 'The result never came back',
  'label.image.failed': 'This image could not be loaded',
  'label.filter.too-large': 'This filter is too large to edit here.',
  'label.panel.move': 'Move panel',

  // Filter kernel.
  'filter.field.reference-without-source':
    '{field} is a reference field with no candidate source declared.',
  'filter.field.duplicate-in-group':
    '{field} is already a condition in this group. Nest a group to ask it something else.',
  'filter.field.unknown': 'The field {field} no longer exists.',
  'filter.group.unknown-operator': 'A condition group must be AND or OR.',
  'filter.kind.unregistered': 'No editor is registered for the {kind} type.',
  'filter.node.invalid': 'This condition could not be read.',
  'filter.operator.unsupported': '{field} does not support {operator}.',
  'filter.tree.too-deep': 'The conditions nest deeper than {max} levels.',
  'filter.tree.too-many-nodes': 'The conditions exceed {max} entries.',
  'filter.value.expected-boolean': 'Choose yes or no.',
  'filter.value.expected-date': 'Enter a date.',
  'filter.value.expected-id': 'Enter an id, or pick a candidate.',
  'filter.value.expected-id-list': 'Enter one or more ids.',
  'filter.value.expects-one': 'This condition takes a single value.',
  'filter.value.expected-text': 'Type what to search for.',
  'label.operator.SEARCH': 'contains',
  'definition.field.search-fields-unknown':
    '{field} searches {missing}, which the definition does not declare.',
  'definition.field.search-mode-invalid':
    '{field} declares an unknown search mode: {value}.',
  'filter.element.root-filter':
    '{field} asks about the whole record, so it cannot be asked of one entry.',
  'filter.value.expected-predicate': 'Describe what an entry must match.',
  'filter.field.holds-no-elements':
    '{field} holds no entries to match against.',
  'label.operator.ELEMENT_MATCH': 'has an entry where',
  'filter.value.expected-entry-list': 'Choose or enter one or more entries.',
  'filter.value.expected-number': 'Enter a number.',
  'filter.value.expected-number-list': 'Enter one or more numbers.',
  'filter.value.expected-number-range': 'Enter a range of two numbers.',
  'filter.value.expected-option-list': 'Choose one or more options.',
  'filter.value.expected-reference-list': 'Choose one or more records.',
  'filter.value.expected-string': 'Enter a value.',
  'filter.value.expected-string-list': 'Enter one or more values.',
  'filter.value.inverted-range': 'The range starts after it ends.',
  'filter.value.relative-too-large':
    'A relative window can reach at most {max} units.',
  'filter.value.required': 'This condition needs a value.',
  'filter.value.unknown-option': '{values} is no longer an option.',
  'filter.value.unknown-time-zone':
    '{timeZone} is not a time zone this browser knows.',
  'filter.value.unparsable-date': 'That date cannot be read.',

  // Shared config.
  'config.refresh.missing': 'This view has no refresh setting.',
  'config.refresh.not-an-integer':
    'The refresh interval must be whole seconds.',
  'config.refresh.too-long':
    'The refresh interval cannot exceed {max} seconds.',
  'config.refresh.too-short':
    'The refresh interval must be at least {min} seconds.',
  'config.filter.invalid': 'The conditions of this view could not be read.',
  'config.filterMode.unknown': 'This view has an unknown filter mode.',
  'config.filterMode.not-simple':
    'These conditions need the advanced editor to be shown in full.',
  'config.invalid': 'This view could not be read.',

  // Record kernel.
  'record.capability.missing':
    '{definition} does not offer a record view any more.',
  'record.card.invalid': 'The card settings could not be read.',
  'record.column.duplicate': 'The column {field} is listed twice.',
  'record.field.not-a-column':
    '{field} is a search or metadata handle, not something a row holds.',
  'record.field.unknown': 'The column {field} no longer exists.',
  'record.layout.unsupported': 'The {layout} layout is not available here.',
  'record.pageSize.not-positive': 'The page size must be a positive number.',
  'record.pageSize.too-large': 'The page size cannot exceed {max}.',
  'record.sort.duplicate': 'The sort already orders by {field}.',
  'record.sort.invalid': 'The sort settings could not be read.',
  'record.sort.not-sortable': '{field} cannot be sorted on.',
  'record.sort.too-many': 'A cursor view sorts on at most {max} fields.',
  'record.summaries.invalid': 'The summary settings could not be read.',
  'record.summary.duplicate': 'The {fn} summary of {field} is listed twice.',
  'record.summary.unsupported': '{field} does not offer the {fn} summary.',
  'record.table.invalid': 'The table settings could not be read.',

  // Analysis kernel.
  'analysis.alias.duplicate': 'The name {alias} is used twice.',
  'analysis.alias.invalid': '{alias} is not a usable name.',
  'analysis.alias.not-a-segment': '{alias} cannot contain a dot.',
  'analysis.alias.reserved': '{alias} uses a reserved prefix.',
  'analysis.any.undeclared': '{field} cannot be shown as a sample value.',
  'analysis.capability.missing':
    '{definition} does not offer an analysis view any more.',
  'analysis.column.duplicate': 'The column {alias} is listed twice.',
  'analysis.column.unknown-alias': 'The column {alias} is not in this result.',
  'analysis.config.malformed': 'This analysis has no usable shape.',
  'analysis.constant.not-finite': 'A constant must be a finite number.',
  'analysis.count.undeclared': 'This dataset does not offer a row count.',
  'analysis.derived.unknown-metric':
    'The derived metric refers to {metric}, which is not declared before it.',
  'analysis.distinctCount.undeclared':
    '{field} does not offer distinct counts.',
  'analysis.element.undeclared': 'The nested path {path} is not available.',
  'analysis.elementFilter.empty':
    'This filter has no conditions, so every entry is expanded.',
  'analysis.elementFilter.incomplete':
    'Give {field} a value, or every entry is expanded.',
  'analysis.elements.too-many': 'Too many nested paths for this dataset.',
  'analysis.expression.divide-by-zero': 'This expression divides by zero.',
  'analysis.expression.malformed': 'This metric has no usable expression.',
  'analysis.expression.too-deep':
    'The expression nests deeper than {max} levels.',
  'analysis.expression.too-many-nodes': 'The expressions exceed {max} entries.',
  'analysis.expressions.undeclared':
    'This dataset does not offer computed expressions.',
  'analysis.field.unknown': 'The field {field} is not available here.',
  'analysis.function.unsupported': '{field} does not offer {fn}.',
  'analysis.group.blank-missing-key':
    'The placeholder for missing values is empty.',
  'analysis.group.blank-time-zone': 'The time zone is empty.',
  'analysis.group.dense-not-alone':
    'A gap-filling date grouping must be the only grouping.',
  'analysis.group.interval-not-positive':
    'A histogram interval must be greater than zero.',
  'analysis.group.unit-unsupported': 'The {unit} unit is not available here.',
  'analysis.group.unsupported': '{field} cannot be grouped by {type}.',
  'analysis.groups.too-many': 'Too many groupings for this dataset.',
  'analysis.having.malformed': 'This result filter has no usable shape.',
  'analysis.having.requires-group':
    'Filtering the result needs at least one grouping.',
  'analysis.having.too-deep':
    'The result filter nests deeper than {max} levels.',
  'analysis.having.too-many-nodes': 'The result filter exceeds {max} entries.',
  'analysis.having.undeclared':
    'This dataset does not offer filtering the result.',
  'analysis.having.unknown-metric':
    'The filter refers to {metric}, which is not a usable metric.',
  'analysis.limit.not-positive': 'The row limit must be a positive number.',
  'analysis.limit.too-large': 'The row limit cannot exceed {max}.',
  'analysis.metric.type-unknown': 'The metric type {type} is not available.',
  'analysis.metricFilter.empty':
    'This filter has no conditions, so the metric covers every record.',
  'analysis.metricFilter.incomplete':
    'Give {field} a value, or the metric covers every record.',
  'analysis.metricFilter.not-scalar':
    '{field} has no single value for a metric filter to test.',
  'analysis.metrics.empty': 'An analysis needs at least one metric.',
  'analysis.metrics.too-many': 'Too many metrics for this dataset.',
  'analysis.percentile.out-of-range':
    'A percentile must be between 0 and 100, exclusive.',
  'analysis.percentile.undeclared': '{field} does not offer percentiles.',
  'analysis.sort.duplicate': 'The sort already orders by {alias}.',
  'analysis.sort.requires-group': 'Sorting needs at least one grouping.',
  'analysis.sort.too-many': 'A result sorts on at most {max} columns.',
  'analysis.sort.unknown-alias':
    'Sorting refers to {alias}, which is not shown.',

  // Charts.
  'chart.combo.series-type-missing':
    'Every series of a combo chart needs its own type.',
  'chart.family.missing': 'The {type} chart has no {family} settings yet.',
  'chart.funnel.duplicate-stage': 'A funnel stage is listed twice.',
  'chart.funnel.metrics-need-no-group':
    'A metric funnel cannot be grouped as well.',
  'chart.funnel.too-few-stages': 'A funnel needs at least two stages.',
  'chart.group.unconsumed': 'The chart does not use every grouping: {groups}.',
  'chart.group.unknown': '{alias} is not a grouping of this analysis.',
  'chart.heatmap.same-axes': 'A heatmap needs two different axes.',
  'chart.metric.needs-no-group': 'A metric card cannot be grouped.',
  'chart.metric.trend-alias-mismatch':
    'The trend must use the {alias} grouping.',
  'chart.metric.trend-needs-one-date-group':
    'A trend needs exactly one date grouping.',
  'chart.metric.trend-not-additive':
    'A trend headline needs an additive metric, not {metric}.',
  'chart.metric.unknown': '{alias} is not a metric of this analysis.',
  'chart.pie.maxSlices-not-additive':
    'Merging the smallest slices needs an additive metric.',
  'chart.pie.maxSlices-too-small': 'Keep at least two slices.',
  'chart.referenceLine.empty-axis':
    'A reference line needs a series on its axis.',
  'chart.scatter.same-metrics': 'A scatter plot needs two different metrics.',
  'chart.splitBy.needs-one-series': 'A split chart shows exactly one metric.',
  'chart.splitBy.same-as-x': 'The split cannot repeat the horizontal axis.',
  'chart.type.unknown': 'This chart type is not available.',

  // Dashboard kernel.
  'dashboard.binding.global-duplicate':
    '{field} is mapped twice on this panel.',
  'dashboard.binding.global-unknown':
    '{field} is not a filter field of this dashboard.',
  'dashboard.binding.kind-mismatch':
    'A {global} field cannot filter a {panel} field.',
  'dashboard.binding.missing':
    'This panel does not carry the {field} filter, so it cannot be filtered with the others.',
  'dashboard.binding.panel-unknown':
    '{field} is not a field of the view this panel shows.',
  'dashboard.field.duplicate': 'The filter field {field} is declared twice.',
  'dashboard.field.name-empty': 'A filter field needs a name.',
  'dashboard.field.name-invalid': '{field} is not a usable field name.',
  'dashboard.layout.invalid': 'This panel has an unusable position or size.',
  'dashboard.layout.missing': 'This panel has no position.',
  'dashboard.layout.out-of-grid':
    'This panel reaches past the {columns} columns of the grid.',
  'dashboard.link.label-empty': 'A link needs a label.',
  'dashboard.links.too-many': 'A links panel holds at most {max} links.',
  'dashboard.markdown.too-long': 'A note holds at most {max} characters.',
  'dashboard.panel.failed': 'This panel could not be shown: {reason}',
  'dashboard.panel.id-duplicate': 'Two panels share the id {id}.',
  'dashboard.panel.id-empty': 'A panel needs an id.',
  'dashboard.panel.kind-unsupported':
    '{instance} is not a record or analysis view.',
  'dashboard.panel.scope-too-narrow':
    'A {scope} dashboard cannot show a {instance} view, which others cannot read.',
  'dashboard.panel.unavailable': 'The view this panel shows is unavailable.',
  'dashboard.panel.unknown-kind': 'The {kind} panel type is not available.',
  'dashboard.panels.too-many': 'A dashboard holds at most {max} panels.',
  'dashboard.shape.invalid':
    'This part of the dashboard is not in the expected shape.',
  'dashboard.url.unsupported-scheme':
    'Only http, https, mailto and relative links can be shown.',

  // Definition admission.
  'definition.analysis.default-limit-too-large':
    'The default row limit exceeds the maximum.',
  'definition.analysis.element-field-unknown':
    '{path} declares no field named {field}.',
  'definition.analysis.element-undeclared':
    'The analysis expands {path}, which is not a field holding elements.',
  'definition.analysis.element-path-invalid':
    '{path} is not a usable field path.',
  'definition.analysis.field-unknown':
    'The analysis capability names {field}, which the definition does not declare.',
  'definition.analysis.limit-invalid':
    'An analysis limit must be a positive whole number.',
  'definition.analysis.no-metric':
    'The analysis capability offers no metric to start from.',
  'definition.field.duplicate': 'The field {field} is declared twice.',
  'definition.field.string-comparison-invalid':
    '{field} declares an unknown text comparison: {value}.',
  'definition.field.kind-unregistered':
    '{field} uses the unregistered type {kind}.',
  'definition.field.name-invalid': '{field} is not a usable field name.',
  'definition.id.separator': 'A definition id cannot contain {separator}.',
  'definition.record.layouts-empty': 'The record capability offers no layout.',
  'definition.record.row-key-unknown':
    'The row key {field} is not a declared field.',
  'definition.view.id-duplicate': 'Two views share the id {id}.',
  'definition.view.id-separator': 'A view id cannot contain {separator}.',
  'definition.view.kind-mismatch':
    'A {kind} view does not belong to a {definition} definition.',

  // Runtime and commands.
  'runtime.kind.not-declared': '{definition} does not offer a {kind} view.',
  'runtime.options.unresolved':
    'No candidate source is configured for {source}.',
  'runtime.query.failed': 'The source answered: {reason}',
  'runtime.query.queue-full':
    'Too many queries at once; try again in a moment.',
  'view.abandon.failed': 'That write could not be set aside.',
  'view.config.invalid': 'Fix what this view reports before saving it.',
  'view.create.forbidden': 'You may not create views here.',
  'view.definition.invalid':
    'The {id} definition has {issues} problem(s) and cannot be opened.',
  'view.definition.not-found': 'No definition named {id}.',
  'view.delete.failed': 'This view could not be deleted.',
  'view.list.failed': 'The list of views could not be loaded.',
  'view.list.reserved-id':
    'The stored view {id} uses a reserved id and was skipped.',
  'view.open.failed': 'This view could not be opened.',
  'view.open.not-found': 'No view named {id}.',
  'view.preferences.default-forbidden': 'You may not set the default view.',
  'view.preferences.failed': 'Your view preferences could not be saved.',
  'view.preferences.reorder-forbidden': 'You may not reorder views.',
  'view.rename.failed': 'This view could not be renamed.',
  'view.resolve.failed': 'That conflict could not be resolved.',
  'view.retry.failed': 'That write could not be retried.',
  'view.runtime.not-owned': 'This view is not open here any more.',
  'view.save-as.failed': 'This view could not be saved as a copy.',
  'view.save.failed': 'This view could not be saved.',
  'view.system.read-only': 'A built-in view cannot be changed ({action}).',
  'view.title.empty': 'A view needs a title.',
  'view.write.conflict-unreadable':
    'Someone else saved first, and their version could not be read.',
  'view.write.not-a-conflict': 'That write is {kind}, not a conflict.',
  'view.write.not-pending': 'That write is already settled.',
  'view.write.in-flight':
    'This view is already being saved; wait for that to finish.',
  'view.write.unknown-pending':
    'The last {action} has not been confirmed yet. Retry it or abandon it before writing again.',
  'view.write.conflict': 'Someone else saved this view first.',
  'view.write.forbidden': 'You may not write to this view.',
  'view.write.invalid': 'The server refused this write.',
  'view.write.not_found': 'This view no longer exists.',
  'view.write.unavailable': 'The server could not be reached.',

  // `/react` composes `<command>.<outcome>`; these say more than the command
  // alone, and anything not named here falls back along the dots.
  'view.open.failed.not_found': 'This view no longer exists.',
  'view.open.failed.forbidden': 'You may not open this view.',
  'view.open.failed.unavailable':
    'This view could not be loaded: the server could not be reached.',
  'view.list.failed.unavailable':
    'The list of views could not be loaded: the server could not be reached.',
});
