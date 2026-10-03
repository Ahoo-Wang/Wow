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
  sameJson,
  type AnalysisDateUnit,
  type AnalysisGroup,
  type AnalysisGroupType,
  type AnalysisViewConfig,
  type ChartSpec,
  type FieldDefinition,
  type FilterLeaf,
  type FilterNode,
  type FilterValue,
  type RecordData,
} from '../model/index.js';
import {
  isFilterLeaf,
  isSimpleTree,
  operatorsOf,
  readInstant,
  type FieldKind,
  type FieldKindRegistry,
} from '../filter/index.js';
import { fitChartSlots } from './chartSlots.js';
import { switchChartType } from './chartSwitch.js';
import { chartUnfit, fitCharts } from './fitCharts.js';
import { drillFilter } from './drillFilter.js';
import { familyRules } from './familyRules.js';
import { aliasOf, groupFacts, groupOfType } from './defaults.js';
import { bucketRange } from './buckets.js';

/*
 * One group of an aggregation result, turned back into the conditions that
 * select the records it was computed from.
 *
 * This is the inverse of bucketing (K1): a `TERMS` group is one value, a
 * `HISTOGRAM` group is the half-open interval `[key, key + interval)`, and a
 * `DATE_HISTOGRAM` group is the bucket `[start, next start)` cut in the zone
 * the histogram was cut in. A `DATE_PART` group — every Monday — has no
 * inverse a condition can hold, so a row with one leads to no records. The analysis kernel hands out conditions and
 * nothing else (K6): what view they open, and under which columns, is the
 * runtime's and the record kernel's to say, because `analysis` and `record`
 * never import each other.
 */

export interface DrillContext {
  /** The engine's zone: the one a histogram that names none was cut in. */
  timeZone: string;
}

/**
 * The conditions that select the records behind one row of an analysis
 * result, or `null` when no condition can say it.
 *
 * Over the root, one or two per group. Over one level of expanded elements
 * (D38) the row is a count of elements, and a record view sees root
 * documents: the row's conditions are asked of one element of the array —
 * one element match holding the level's own gate and every group, so a
 * record is one with an element that is this group, not one whose elements
 * are this group between them. Over elements of elements there is no such
 * sentence yet (`drillGap`).
 */
export function drillConditions(
  config: AnalysisViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  row: RecordData,
  context: DrillContext,
): FilterNode[] | null {
  const drilled = drillGroups(config, fields, kinds, row, context);
  return drilled && drillRecordConditions(config, fields, kinds, drilled);
}

/**
 * Why a group of this result, which can be named, cannot be followed to the
 * records behind it (D38): its counting unit is an element of an element,
 * and a condition over root documents reaches one level of an array only.
 */
type DrillGap = 'nested-elements';

export function drillGap(
  config: Pick<AnalysisViewConfig, 'elements'>,
): DrillGap | undefined {
  return (config.elements?.length ?? 0) > 1 ? 'nested-elements' : undefined;
}

/**
 * The conditions a record view opens drilled groups under — the groups of
 * `drillGroups` or `drillSpan` — as `drillConditions` says them; `null` over
 * elements of elements, or over an array the definition does not let a
 * condition match into.
 */
export function drillRecordConditions(
  config: AnalysisViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  drilled: readonly DrilledGroup[],
): FilterNode[] | null {
  const conditions = drilled.flatMap(entry => entry.conditions);
  const elements = config.elements ?? [];
  if (elements.length === 0) return conditions;
  if (drillGap(config)) return null;
  const [element] = elements;
  const holder = fields.find(field => field.name === element.path);
  const kind = holder && kinds.get(holder.kind);
  if (!holder || !kind || !operatorsOf(holder, kind).includes('ELEMENT_MATCH'))
    return null;
  // The level's gate first and the groups after it, one "all of" where the
  // gate was one, as `drillFilter` joins a row to a range — unless a group
  // bounds a field the gate bounds too, which one group may not say twice:
  // then the gate is a group of its own inside the match.
  const gate = element.filter ?? { op: 'and', children: [] };
  const named = new Set(conditions.map(condition => condition.field));
  const predicate = gate.children.some(
    child => isFilterLeaf(child) && named.has(child.field),
  )
    ? { op: 'and' as const, children: [gate, ...conditions] }
    : drillFilter(gate, conditions);
  return [
    {
      field: holder.name,
      operator: 'ELEMENT_MATCH',
      // A predicate is a tree stored as a leaf's value, which the value type
      // knows only as JSON; the element-match kind reads it back as one.
      value: predicate as unknown as FilterValue,
    },
  ];
}

/**
 * The fields a result's groups name, by their config spelling: the root's,
 * or the innermost expanded element's, each qualified by the chain above it
 * as `analysisScope` names them — `null` where the chain names an array the
 * definition does not hold.
 */
export function drilledFields(
  config: Pick<AnalysisViewConfig, 'elements'>,
  fields: readonly FieldDefinition[],
): Map<string, FieldDefinition> | null {
  let held = fields;
  let absolute = '';
  for (const element of config.elements ?? []) {
    const holder = held.find(field => field.name === element.path);
    if (!holder?.elements) return null;
    absolute = absolute === '' ? element.path : `${absolute}.${element.path}`;
    held = holder.elements;
  }
  const prefix = absolute === '' ? '' : `${absolute}.`;
  return new Map(
    held.map(field => {
      const named = { ...field, name: `${prefix}${field.name}` };
      return [named.name, named] as const;
    }),
  );
}

/** One dimension of a pressed row, and the conditions that select it. */
export interface DrilledGroup {
  group: AnalysisGroup;
  /** The row's value under the group's alias: a key, a bucket's start. */
  value: unknown;
  conditions: FilterLeaf[];
}

/**
 * The groups of one pressed row, dimension by dimension, in the config's
 * order: what a menu names the group pressed by, where one dimension may
 * read better as its value than as its conditions — a month is 「2026年9月」,
 * not the two instants that bound it. Over expanded elements each names the
 * innermost element's fields (`drilledFields`), at any depth: the group can
 * be named even where it cannot be followed to records
 * (`drillRecordConditions`).
 */
export function drillGroups(
  config: AnalysisViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  row: RecordData,
  context: DrillContext,
): DrilledGroup[] | null {
  const byName = drilledFields(config, fields);
  if (!byName) return null;
  const drilled: DrilledGroup[] = [];
  for (const group of config.groups) {
    // A band of a computed number names no field a condition could hold.
    if (group.field === undefined) return null;
    const field = byName.get(group.field);
    if (!field) return null;
    const value = row[group.alias];
    const conditions = conditionsOf(group, value, field, kinds, context);
    if (conditions === null) return null;
    drilled.push({ group, value, conditions });
  }
  return drilled;
}

/**
 * The smallest set of conditions that selects the records behind two groups
 * and every bucket between them along time (D33 Q52: a brushed stretch of a
 * time axis, or a range of rows picked from the table): a date dimension
 * becomes one range from the earlier bucket's start to the later bucket's
 * end — `[first start, last end)`, each end where `bucketRange` puts it, so
 * a stretch across a daylight-saving change is as long as the calendar
 * says. A dimension on which the two agree narrows to that value as a press
 * on one group does; one on which they differ, or that either leaves out
 * (a brush along the axis names no series), is not narrowed at all.
 *
 * Over expanded elements the conditions name the element's fields, as
 * `drillGroups`'s do. `null` where no condition can say it — a field the
 * definition lacks, a bound that reads as no instant — and where nothing
 * spans time: without a date dimension the two are two groups, not a
 * stretch, and a menu over them would ask a question nobody pressed.
 */
export function drillSpan(
  config: AnalysisViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  first: RecordData,
  last: RecordData,
  context: DrillContext,
): DrilledGroup[] | null {
  const byName = drilledFields(config, fields);
  if (!byName) return null;
  const drilled: DrilledGroup[] = [];
  let spans = false;
  for (const group of config.groups) {
    if (!(group.alias in first) || !(group.alias in last)) continue;
    if (group.field === undefined) return null;
    const field = byName.get(group.field);
    if (!field) return null;
    const from = first[group.alias];
    const to = last[group.alias];
    if (group.type === 'DATE_HISTOGRAM') {
      const span = spanOf(group, from, to, context);
      if (!span) return null;
      spans = true;
      drilled.push(span);
      continue;
    }
    if (!sameJson(from, to)) continue;
    const conditions = conditionsOf(group, from, field, kinds, context);
    if (conditions === null) return null;
    drilled.push({ group, value: from, conditions });
  }
  return spans ? drilled : null;
}

/**
 * One date dimension from the earlier of two buckets to the later, as the
 * one range a press on a single bucket already is — the same `BETWEEN`,
 * closed a millisecond before the later bucket's end (K1).
 */
function spanOf(
  group: Extract<AnalysisGroup, { type: 'DATE_HISTOGRAM' }>,
  a: unknown,
  b: unknown,
  context: DrillContext,
): DrilledGroup | null {
  const one = readInstant(a)?.ms;
  const other = readInstant(b)?.ms;
  if (one === undefined || other === undefined) return null;
  const start = Math.min(one, other);
  const timeZone = group.timeZone ?? context.timeZone;
  const end = bucketRange(group.unit, Math.max(one, other), timeZone).to;
  return {
    group,
    value: start === one ? a : b,
    conditions: [
      {
        field: group.field,
        operator: 'BETWEEN',
        value: {
          type: 'absolute',
          from: new Date(start).toISOString(),
          to: new Date(end - 1).toISOString(),
          timeZone,
        },
      },
    ],
  };
}

function conditionsOf(
  group: AnalysisGroup,
  value: unknown,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  context: DrillContext,
): FilterLeaf[] | null {
  switch (group.type) {
    case 'TERMS': {
      // The sentinel bucket holds the records that have no value — and any
      // whose value is the sentinel itself, which the condition cannot tell
      // apart and does not try to.
      if (
        value === null ||
        value === undefined ||
        (group.missingKey !== undefined && value === group.missingKey)
      )
        return [{ field: group.field, operator: 'IS_NULL', value: null }];
      return [equal(group.field, value, field, kinds)];
    }
    case 'HISTOGRAM': {
      if (group.field === undefined) return null;
      if (typeof value !== 'number' || !Number.isFinite(value)) return null;
      return [
        { field: group.field, operator: 'GTE', value },
        { field: group.field, operator: 'LT', value: value + group.interval },
      ];
    }
    case 'DATE_HISTOGRAM': {
      const start = readInstant(value)?.ms;
      if (start === undefined) return null;
      const timeZone = group.timeZone ?? context.timeZone;
      const range = bucketRange(group.unit, start, timeZone);
      // `BETWEEN` closes both ends and the bucket is half-open, so the upper
      // bound is the last millisecond inside it (K1). A `LT` on a datetime
      // would say it exactly, but that operator would reach every date
      // editor in the package; a millisecond is cheaper than a control.
      return [
        {
          field: group.field,
          operator: 'BETWEEN',
          value: {
            type: 'absolute',
            from: new Date(range.from).toISOString(),
            to: new Date(range.to - 1).toISOString(),
            timeZone,
          },
        },
      ];
    }
    case 'DATE_PART':
      // 「周一」 is every Monday the range holds, and no condition a view
      // can store says that: a date condition is one stretch of time. The
      // group is still named (a menu reads it), but it leads to no records.
      return null;
  }
}

/**
 * "This field is this value", in the operator the field's kind understands:
 * `EQ` where the kind has it, otherwise the kind's one-of-these — an enum
 * only knows `IN`, a reference carries its label beside the id.
 */
function equal(
  name: string,
  value: unknown,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
): FilterLeaf {
  const kind = kinds.get(field.kind);
  const scalar = value as string | number | boolean;
  if (kind?.operators.includes('EQ'))
    return { field: name, operator: 'EQ', value: scalar };
  if (field.kind === 'reference')
    return {
      field: name,
      operator: 'IN',
      value: { items: [{ id: scalar, label: String(scalar) }] },
    };
  return { field: name, operator: 'IN', value: [scalar] };
}

/**
 * The two follow-up questions that stay analyses (D20 追问): "only this
 * group" narrows the range to the row, "split by" narrows it and asks the
 * same question by another dimension. Each is a patch over the config that
 * ran; the workbench opens the patched config as a view of its own beside
 * the one pressed, which it leaves as it was.
 */
export function focusOn(
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
): Pick<AnalysisViewConfig, 'filter' | 'filterMode'> {
  const filter = drillFilter(config.filter, conditions);
  return {
    filter,
    // A row's conditions flatten into a simple tree; only an already
    // advanced range stays one.
    filterMode: isSimpleTree(filter) ? config.filterMode : 'advanced',
  };
}

export function splitBy(
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
  group: AnalysisGroup,
  /** The metrics that are moments (`momentMetrics`), which no mark measures. */
  moments?: ReadonlySet<string>,
): Pick<
  AnalysisViewConfig,
  'filter' | 'filterMode' | 'groups' | 'sort' | 'table' | 'chart'
> {
  const groups = [group];
  return {
    ...focusOn(config, conditions),
    groups,
    // The sort named the dimensions that are gone; the table's columns and
    // the chart's slots follow the new shape.
    sort: [],
    table: { ...config.table, columns: [] },
    chart: splitChart(config, group, moments),
  };
}

/**
 * The chart a split draws: the one pressed, re-fitted to the new dimension,
 * when it can place that dimension; otherwise the chart the new shape reads
 * best as (`fitCharts`' recommendation, bars when there is none), still
 * measuring what the one pressed measured (`switchChartType`).
 *
 * A chart cannot place the new dimension when its family does not fit the
 * new shape at all — a calendar split by a channel, a candlestick or a
 * river by anything but a date (`chartUnfit`) — or when its slot reads the
 * old dimension's values rather than its shape: a map's regions are names
 * its geography has areas for, and a funnel's stages are the old
 * dimension's values in their business order. Kept, those drew a blank map
 * that said every group was off it, or a funnel of stages nobody ordered.
 */
function splitChart(
  config: AnalysisViewConfig,
  group: AnalysisGroup,
  moments: ReadonlySet<string> = new Set(),
): ChartSpec {
  const groups = [group];
  const kept = fitChartSlots(config.chart, groups, config.metrics, moments);
  const placed =
    chartUnfit({ groups, metrics: config.metrics, chart: kept }, moments) ===
      null && !readsValuesOf(config, group);
  if (placed) return kept;
  const fits = fitCharts({ groups, metrics: config.metrics, moments });
  const type =
    CHART_TYPES_BY_FIT.find(candidate => fits[candidate].recommended) ?? 'bar';
  return fitChartSlots(
    switchChartType(config.chart, type),
    groups,
    config.metrics,
    moments,
  );
}

/** The types `fitCharts` may recommend. */
const CHART_TYPES_BY_FIT = ['metric', 'line', 'bar'] as const;

/**
 * Whether the chart's slot is bound to the values of a dimension other than
 * `group`'s field: a map's region, or the dimension a funnel's stages are
 * the ordered values of.
 */
function readsValuesOf(
  config: AnalysisViewConfig,
  group: AnalysisGroup,
): boolean {
  const bound = familyRules(config.chart.type)?.boundTo(config.chart);
  if (bound === undefined) return false;
  const field = config.groups.find(entry => entry.alias === bound)?.field;
  return field !== group.field;
}

/**
 * The dimension a field becomes when a group is split by it: the first way
 * the definition offers to group it, as the tray's 「添加维度」 takes it —
 * the order a definition lists its group types in is its author saying how
 * the field is first looked at, and a follow-up has no better reason to
 * look at it otherwise. Shaped by the one builder, `groupOfType`. The alias is the one a fresh config's dimension
 * carries (`aliasOf(field, 'group')`), so the split is savable as it stands.
 */
export function groupFor(
  field: FieldDefinition,
  offered: {
    groups: readonly AnalysisGroupType[];
    dateUnits: readonly AnalysisDateUnit[];
    missingKey?: boolean;
  },
  kind?: Pick<FieldKind, 'singleString'>,
): AnalysisGroup {
  // A field is offered as a split only when it has a group type at all.
  const type = offered.groups[0] ?? 'TERMS';
  return groupOfType(
    groupFacts(field, offered.dateUnits, kind, offered),
    type,
    aliasOf(field.name, 'group'),
  );
}
