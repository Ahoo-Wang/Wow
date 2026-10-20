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

import { AggregationGroupType } from "@ahoo-wang/wow-client";
import type {
  DataViewDefinition,
  FieldDefinition,
  FilterNode,
  RecordViewConfig,
  SystemView,
} from "@ahoo-wang/wow-view-engine";
import { textKeys, type Words } from "./words.ts";

/** The definition's id: what saved views and routes name it by. */
export const DATASETS = "analytics-catalog-datasets";

/** The source key the definition reads: the `dataset` snapshots. */
export const DATASETS_SOURCE = "analytics-catalog.dataset";

/** Where a dataset is in its life (`DatasetStatus`). */
export const DATASET_STATUSES = [
  "DRAFT",
  "PUBLISHED",
  "NEEDS_REVISION",
  "RETIRED",
] as const;
export type DatasetStatus = (typeof DATASET_STATUSES)[number];

/** Every word the definition shows, per language. */
export const DATASET_WORDS = {
  en: {
    title: "Datasets",
    recordNoun: "dataset",
    id: "Dataset",
    context: "Context",
    name: "Name",
    datasetTitle: "Title",
    status: "Status",
    statusDraft: "Draft",
    statusPublished: "Published",
    statusNeedsRevision: "Needs revision",
    statusRetired: "Retired",
    current: "Version served",
    stillServing: "Still serving",
    dropped: "Views dropped",
    viewAll: "All",
    viewPublished: "Published",
    viewNeedsRevision: "Needs revision",
    viewDrafts: "Drafts",
    viewRetired: "Retired",
    viewByStatus: "By status",
  },
  "zh-CN": {
    title: "数据集",
    recordNoun: "数据集",
    id: "数据集",
    context: "上下文",
    name: "名称",
    datasetTitle: "标题",
    status: "状态",
    statusDraft: "草稿",
    statusPublished: "已发布",
    statusNeedsRevision: "需要修订",
    statusRetired: "已下线",
    current: "提供的版本",
    stillServing: "仍在提供",
    dropped: "视图已删除",
    viewAll: "全部",
    viewPublished: "已发布",
    viewNeedsRevision: "需要修订",
    viewDrafts: "草稿",
    viewRetired: "已下线",
    viewByStatus: "按状态统计",
  },
} satisfies Words;

const t = textKeys("datasets", DATASET_WORDS.en);

const COLUMNS = [
  "aggregateId",
  "state.title",
  "state.status",
  "state.current",
  "state.stillServing",
];

/** A record view of the datasets `filter` keeps, by name. */
export function datasetsView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: "record",
    filter: { op: "and", children: filter },
    filterMode: "simple",
    refresh: { interval: null },
    sort: [{ field: "aggregateId", direction: "ASC" }],
    pageSize: 20,
    layout: "table",
    summaries: [],
    table: { columns: COLUMNS.map((field) => ({ field })) },
    card: {
      title: "aggregateId",
      fields: ["state.title", "state.status", "state.current"],
    },
    ...overrides,
  };
}

export function status(...values: DatasetStatus[]): FilterNode {
  return { field: "state.status", operator: "IN", value: values };
}

/** The system views: the datasets a data owner works through. */
export function datasetViews(): SystemView[] {
  return [
    { id: "all", title: t.viewAll, config: datasetsView([]) },
    {
      id: "needs-revision",
      title: t.viewNeedsRevision,
      config: datasetsView([status("NEEDS_REVISION")]),
    },
    {
      id: "published",
      title: t.viewPublished,
      config: datasetsView([status("PUBLISHED")]),
    },
    {
      id: "drafts",
      title: t.viewDrafts,
      config: datasetsView([status("DRAFT")]),
    },
    {
      id: "retired",
      title: t.viewRetired,
      config: datasetsView([status("RETIRED")]),
    },
    {
      id: "by-status",
      title: t.viewByStatus,
      config: {
        kind: "analysis",
        filter: { op: "and", children: [] },
        filterMode: "simple",
        refresh: { interval: null },
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: "state.status",
            alias: "status",
          },
        ],
        metrics: [{ alias: "count", type: "COUNT" }],
        sort: [{ alias: "count", direction: "DESC" }],
        limit: 20,
        layout: "table",
        table: { columns: [] },
        chart: {
          type: "bar",
          cartesian: { x: "status", series: [{ metric: "count" }] },
        },
      },
    },
  ];
}

const string = (
  name: string,
  label: FieldDefinition["label"],
): FieldDefinition => ({
  name,
  label,
  kind: "string",
  sortable: true,
});

/**
 * The catalog's `dataset` snapshots as the engine reads them: a wide table
 * over the catalog's sources each (D238 ④, D259), its definition, its
 * versions and where it stands. Written by hand: the paths are the generated
 * `DatasetStateField`s.
 */
export const datasets: DataViewDefinition = {
  id: DATASETS,
  kind: "data",
  source: DATASETS_SOURCE,
  title: t.title,
  recordNoun: t.recordNoun,
  fields: [
    { ...string("aggregateId", t.id), cell: "copyable" },
    string("state.context", t.context),
    string("state.name", t.name),
    string("state.title", t.datasetTitle),
    {
      name: "state.status",
      label: t.status,
      kind: "enum",
      sortable: true,
      cell: "status",
      options: [
        { value: "DRAFT", label: t.statusDraft, tone: "neutral" },
        { value: "PUBLISHED", label: t.statusPublished, tone: "success" },
        {
          value: "NEEDS_REVISION",
          label: t.statusNeedsRevision,
          tone: "danger",
        },
        { value: "RETIRED", label: t.statusRetired, tone: "warning" },
      ],
    },
    { name: "state.current", label: t.current, kind: "number", sortable: true },
    {
      name: "state.stillServing",
      label: t.stillServing,
      kind: "boolean",
      sortable: true,
    },
    {
      name: "state.dropped",
      label: t.dropped,
      kind: "boolean",
      sortable: true,
    },
  ],
  record: {
    rowKey: "aggregateId",
    paging: "paged",
    layouts: ["table", "card"],
    // What the actions read to decide what a dataset takes.
    rowFields: ["state.status", "state.current"],
  },
  analysis: {
    count: true,
    fields: ["state.status", "state.context"].map((field) => ({
      field,
      groups: [AggregationGroupType.TERMS],
      functions: [],
      expressionInput: false,
    })),
  },
  views: datasetViews(),
};
