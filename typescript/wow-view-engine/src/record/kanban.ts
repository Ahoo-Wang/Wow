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
  aggregation,
  type AggregationMetric,
  type AggregationQuery,
  type CursorPage,
  type PagedList,
} from '@ahoo-wang/wow-client/dsl';
import {
  DEFAULT_RUNTIME_LIMITS,
  MAX_KANBAN_LANES,
  isFieldlessKind,
  FilterOperators,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type FilterLeaf,
  type Issue,
  type RecordData,
  type RecordKanbanSpec,
  type RecordPageTarget,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import {
  compileFilter,
  isPlainObject,
  issue,
  mergeFilters,
  type FieldKindRegistry,
  type FilterCompileContext,
} from '../filter/index.js';
import { summaryMetrics } from './compile.js';
import {
  projectRecord,
  projectSummaries,
  type RecordLaneView,
  type RecordProjection,
  type RecordRow,
} from './project.js';

/** One lane a kanban asks for: an option of the lane field, or「其他」. */
export interface KanbanLane {
  /** See `RecordLaneView.id`. */
  id: string;
  /** The option's value; `null` for the「其他」lane. */
  value: string | number | null;
  /** The option the lane is; absent for the「其他」lane. */
  option?: FieldOption;
}

/** The id of the lane of every record whose value is none of the options. */
export const OTHER_LANE_ID = 'other';

/** The aliases the lane aggregation answers under; no summary alias is one. */
const LANE_ALIAS = 'kanbanLane';
const COUNT_ALIAS = 'kanbanCount';

/** A lane's id: its option's value as JSON, so `1` and `'1'` stay apart. */
export function laneId(value: string | number): string {
  return `option:${JSON.stringify(value)}`;
}

/**
 * Whether a field can be a kanban's lane field: an `enum` that declares
 * options, whose order, labels and tones the lanes take.
 */
export function isLaneField(field: FieldDefinition): boolean {
  return (
    field.kind === 'enum' &&
    !isFieldlessKind(field.kind) &&
    (field.options?.length ?? 0) > 0
  );
}

/** The fields a kanban can be laid out by, in the definition's order. */
export function laneFields(
  definition: DataViewDefinition,
): readonly FieldDefinition[] {
  return definition.fields.filter(isLaneField);
}

/**
 * A new view's kanban half: the first lane field, its first
 * `MAX_KANBAN_LANES` options shown and the rest folded away. `undefined`
 * when the definition has no field to lay lanes out by.
 */
export function defaultKanbanSpec(
  definition: DataViewDefinition,
): RecordKanbanSpec | undefined {
  const field = laneFields(definition)[0];
  if (!field) return undefined;
  const folded = (field.options ?? [])
    .slice(MAX_KANBAN_LANES)
    .map(option => option.value);
  return {
    lane: field.name,
    ...(folded.length > 0 ? { hidden: folded } : {}),
  };
}

function laneFieldOf(
  definition: DataViewDefinition,
  config: RecordViewConfig,
): FieldDefinition | undefined {
  const name = config.kanban?.lane;
  return definition.fields.find(field => field.name === name);
}

/** The options a kanban shows: the lane field's, less the folded ones. */
function shownOptions(
  field: FieldDefinition,
  spec: RecordKanbanSpec,
): FieldOption[] {
  const hidden = new Set<unknown>(spec.hidden ?? []);
  return (field.options ?? []).filter(option => !hidden.has(option.value));
}

/**
 * The lanes a kanban asks for, in the order it draws them: the lane field's
 * options as the definition lists them, less the folded ones, then the
 * 「其他」lane. Empty for a config that is no kanban, or whose lane field
 * the definition no longer offers — admission says why.
 */
export function kanbanLanes(
  definition: DataViewDefinition,
  config: RecordViewConfig,
): KanbanLane[] {
  const field = laneFieldOf(definition, config);
  if (!config.kanban || !field || !isLaneField(field)) return [];
  return [
    ...shownOptions(field, config.kanban).map(option => ({
      id: laneId(option.value),
      value: option.value,
      option,
    })),
    { id: OTHER_LANE_ID, value: null },
  ];
}

/**
 * One lane's question: the view's conditions and the lane's, the rest of
 * the config as it is. An option's lane is `IN [value]`; the「其他」lane
 * is `NOT_IN` every option, which also holds the records with no value.
 *
 * The lane's condition is the engine's, never the user's: it is merged in
 * here, the way a host's scope is, and compiled by the same `enum` kind —
 * it is in no config, so nothing shows, saves or edits it.
 */
export function laneConfig(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  lane: KanbanLane,
): RecordViewConfig {
  const field = laneFieldOf(definition, config);
  if (!field) return config;
  const leaf: FilterLeaf =
    lane.value === null
      ? {
          field: field.name,
          operator: FilterOperators.NOT_IN,
          value: (field.options ?? []).map(option => option.value),
        }
      : {
          field: field.name,
          operator: FilterOperators.IN,
          value: [lane.value],
        };
  return {
    ...config,
    filter: mergeFilters(config.filter, { op: 'and', children: [leaf] }),
  };
}

/**
 * Every shown option lane's count and summaries, as one aggregation: the
 * view's conditions and `IN` the shown values, grouped by the lane field,
 * `COUNT` and the config's summaries. `null` when no option lane is shown.
 *
 * The「其他」lane is not in it: a grouping never counts the records with
 * no value, and those are half of what that lane holds.
 */
export function compileKanbanCounts(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationQuery | null {
  const field = laneFieldOf(definition, config);
  if (!field || !config.kanban) return null;
  const values = shownOptions(field, config.kanban).map(option => option.value);
  if (values.length === 0) return null;
  const tree = mergeFilters(config.filter, {
    op: 'and',
    children: [
      { field: field.name, operator: FilterOperators.IN, value: values },
    ],
  });
  return {
    filter: compileFilter(definition.fields, tree, kinds, context),
    groupBy: [aggregation.terms(field.name, LANE_ALIAS)],
    metrics: [
      aggregation.count(COUNT_ALIAS),
      ...summaryMetrics(definition, config),
    ] as [AggregationMetric, ...AggregationMetric[]],
    limit: values.length,
  };
}

/** One lane's page, as the source answered it. */
export interface LanePage {
  lane: KanbanLane;
  page: PagedList<RecordData> | CursorPage<RecordData>;
  /** The page's index, for a paged source; 1 for a cursor's. */
  index: number;
}

/**
 * A kanban's result: every lane projected under the same config and put
 * together, the rows in lane order, each lane with its count, summaries and
 * the page it moves on to.
 *
 * `counts` is the lane aggregation's answer, `null` when it failed or was
 * not asked: an option lane then reads a paged source's total for it, or no
 * count at all. A record is in one lane; one that moved between two loads
 * stays in the lane it reached first, and a refresh puts it right.
 */
export function projectKanban(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  pages: readonly LanePage[],
  counts: readonly RecordData[] | null,
  limits: Pick<RuntimeLimits, 'maxPageWindow'> = DEFAULT_RUNTIME_LIMITS,
): RecordProjection {
  const seen = new Set<unknown>();
  const projected = pages.map(({ lane, page, index }) => {
    const view = projectRecord(definition, config, page, index, limits);
    const rows = unseen(view.rows, seen);
    return {
      view,
      lane: laneView(definition, config, lane, view, rows, index, counts),
    };
  });
  const first = projected[0]?.view ?? emptyView(definition, config, limits);
  const lanes = projected.map(entry => entry.lane);
  return {
    columns: first.columns,
    card: first.card,
    rows: lanes.flatMap(lane => lane.rows),
    paging: first.paging,
    lanes,
  };
}

/**
 * The next page of one lane put after the rows it already has (「加载更多」);
 * every other lane as it was. A row already on screen in any lane is not
 * drawn twice. A view that is no kanban's, or has no such lane, is handed
 * back as it is.
 */
export function appendLane(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  view: RecordProjection,
  id: string,
  page: LanePage['page'],
  index: number,
  limits: Pick<RuntimeLimits, 'maxPageWindow'> = DEFAULT_RUNTIME_LIMITS,
): RecordProjection {
  const lanes = view.lanes;
  if (!lanes?.some(lane => lane.id === id)) return view;
  const seen = new Set<unknown>(view.rows.map(row => row.key));
  const next = projectRecord(definition, config, page, index, limits);
  const added = unseen(next.rows, seen);
  const merged = lanes.map(lane =>
    lane.id === id
      ? {
          ...lane,
          rows: [...lane.rows, ...added],
          next: nextTarget(next, index),
          total: lane.total ?? pagedTotal(next) ?? null,
        }
      : lane,
  );
  return { ...view, rows: merged.flatMap(lane => lane.rows), lanes: merged };
}

/** The rows not yet seen, marking them seen. */
function unseen(rows: readonly RecordRow[], seen: Set<unknown>): RecordRow[] {
  return rows.filter(row => {
    if (seen.has(row.key)) return false;
    seen.add(row.key);
    return true;
  });
}

function laneView(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  lane: KanbanLane,
  view: RecordProjection,
  rows: RecordRow[],
  index: number,
  counts: readonly RecordData[] | null,
): RecordLaneView {
  const counted =
    counts && lane.value !== null ? countRow(counts, lane.value) : undefined;
  const asked = (config.summaries ?? []).length > 0;
  return {
    id: lane.id,
    value: lane.value,
    label: lane.option?.label ?? '',
    ...(lane.option?.tone ? { tone: lane.option.tone } : {}),
    rows,
    total:
      counts && lane.value !== null
        ? countOf(counted)
        : (pagedTotal(view) ?? null),
    next: nextTarget(view, index),
    // A lane the aggregation has no group for holds no record: its sums
    // read over nothing, which is what an empty stage sums to.
    summaries:
      asked && counts && lane.value !== null
        ? projectSummaries(definition, config, {
            scope: 'total',
            result: [counted ?? {}],
          })
        : null,
  };
}

/**
 * The aggregation's row for one option. A store may hand a numeric code
 * back as text, so a value matches its own text too.
 */
function countRow(
  counts: readonly RecordData[],
  value: string | number,
): RecordData | undefined {
  return (
    counts.find(row => row[LANE_ALIAS] === value) ??
    counts.find(row => String(row[LANE_ALIAS]) === String(value))
  );
}

/** A group's count; a group the aggregation has not is an empty lane. */
function countOf(row: RecordData | undefined): number {
  const count = row?.[COUNT_ALIAS];
  return typeof count === 'number' && Number.isFinite(count) ? count : 0;
}

/** A lane's count from its page, when the source counted it exactly. */
function pagedTotal(view: RecordProjection): number | undefined {
  return view.paging.mode === 'paged' && view.paging.exact !== false
    ? view.paging.total
    : undefined;
}

function nextTarget(
  view: RecordProjection,
  index: number,
): RecordPageTarget | null {
  const paging = view.paging;
  if (!paging.hasNext) return null;
  return paging.mode === 'cursor'
    ? { cursor: paging.nextCursor }
    : { index: index + 1 };
}

function emptyView(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  limits: Pick<RuntimeLimits, 'maxPageWindow'>,
): RecordProjection {
  return projectRecord(
    definition,
    config,
    { list: [], total: 0, exact: true },
    1,
    limits,
  );
}

/**
 * The kanban half's shape, checked with the rest of the skeleton: a
 * config from a store may carry anything under that name, and the rules
 * below read it as the type promises.
 */
export function kanbanShapeIssues(config: RecordViewConfig): Issue[] {
  const kanban: unknown = config.kanban;
  if (kanban === undefined) return [];
  const valid =
    isPlainObject(kanban) &&
    typeof kanban.lane === 'string' &&
    (kanban.hidden === undefined ||
      (Array.isArray(kanban.hidden) &&
        kanban.hidden.every(
          value => typeof value === 'string' || typeof value === 'number',
        )));
  return valid ? [] : [issue('record.kanban.invalid', ['kanban'])];
}

/**
 * The kanban's rules, for a config whose layout is the kanban: it has its
 * half, the lane field is one a kanban can be laid out by, and the lanes it
 * shows are no more than `MAX_KANBAN_LANES`. Under another layout the half
 * stored waits, like the card's does under a table, and is judged when the
 * view switches back.
 */
export function validateKanban(
  definition: DataViewDefinition,
  config: RecordViewConfig,
): Issue[] {
  if (config.layout !== 'kanban') return [];
  const spec = config.kanban;
  if (!spec) return [issue('record.kanban.missing', ['kanban'])];
  const field = laneFieldOf(definition, config);
  if (!field)
    return [
      issue('record.field.unknown', ['kanban', 'lane'], { field: spec.lane }),
    ];
  if (!isLaneField(field))
    return [
      issue('record.kanban.lane-not-enum', ['kanban', 'lane'], {
        field: field.name,
      }),
    ];
  const shown = shownOptions(field, spec).length;
  return shown > MAX_KANBAN_LANES
    ? [
        issue('record.kanban.too-many-lanes', ['kanban', 'hidden'], {
          count: shown,
          max: MAX_KANBAN_LANES,
        }),
      ]
    : [];
}
