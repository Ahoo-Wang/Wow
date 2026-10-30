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

import { AggregationGroupType } from "@ahoo-wang/wow-client/dsl";
import type {
  DataViewDefinition,
  FieldDefinition,
  FilterNode,
  RecordViewConfig,
  SystemView,
} from "@ahoo-wang/wow-view-engine";
import { textKeys, type Words } from "./words.ts";

/** The definition's id: what saved views and routes name it by. */
export const SOURCES = "analytics-catalog-sources";

/** The source key the definition reads: the `source` snapshots. */
export const SOURCES_SOURCE = "analytics-catalog.source";

/** Every word the definition shows, per language. */
export const SOURCE_WORDS = {
  en: {
    title: "Sources",
    recordNoun: "source",
    id: "Source ID",
    connection: "Connection",
    database: "Database",
    table: "Table",
    tableEngine: "Table engine",
    kind: "Kind",
    kindAnalyst: "Analyst's table",
    kindWow: "Wow table",
    status: "Status",
    statusDraft: "Draft",
    statusReviewed: "Reviewed",
    statusAdopted: "Adopted",
    missing: "Table gone",
    platform: "Platform dimension",
    approved: "Approved",
    pending: "Lower protection pending",
    tenantColumn: "Tenant column",
    ownerColumn: "Owner column",
    spaceColumn: "Space column",
    context: "Context",
    aggregate: "Aggregate",
    writer: "Written by",
    viewAll: "All",
    viewDrafts: "To review",
    viewWow: "Wow tables",
    viewAnalyst: "Analysts' tables",
    viewUnapproved: "Awaiting approval",
    viewMissing: "Tables gone",
    viewByStatus: "By status",
  },
  "zh-CN": {
    title: "数据源",
    recordNoun: "数据源",
    id: "数据源 ID",
    connection: "连接",
    database: "库",
    table: "表",
    tableEngine: "表引擎",
    kind: "类型",
    kindAnalyst: "分析师的表",
    kindWow: "Wow 表",
    status: "状态",
    statusDraft: "草稿",
    statusReviewed: "已审查",
    statusAdopted: "已采纳",
    missing: "表已不在",
    platform: "平台维度",
    approved: "已审批",
    pending: "待审批的降低保护",
    tenantColumn: "租户列",
    ownerColumn: "所有者列",
    spaceColumn: "空间列",
    context: "上下文",
    aggregate: "聚合",
    writer: "写入账户",
    viewAll: "全部",
    viewDrafts: "待审查",
    viewWow: "Wow 表",
    viewAnalyst: "分析师的表",
    viewUnapproved: "待审批",
    viewMissing: "表已不在",
    viewByStatus: "按状态统计",
  },
} satisfies Words;

const t = textKeys("sources", SOURCE_WORDS.en);

const COLUMNS = [
  "state.database",
  "state.table",
  "state.kind",
  "state.status",
  "state.tenantColumn",
  "state.platform",
  "state.approved",
  "state.missing",
];

/**
 * How often a list of sources or datasets is read again, in seconds: the
 * catalog streams no changes of its snapshots, and a source changes on its
 * own when the warehouse does, a dataset when another data owner acts on it.
 */
export const REFRESH_SECONDS = 15;

/** A record view of the sources `filter` keeps, by database and table. */
export function sourcesView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: "record",
    filter: { op: "and", children: filter },
    filterMode: "simple",
    refresh: { interval: REFRESH_SECONDS },
    sort: [
      { field: "state.database", direction: "asc" },
      { field: "state.table", direction: "asc" },
    ],
    pageSize: 20,
    layout: "table",
    summaries: [],
    table: { columns: COLUMNS.map((field) => ({ field })) },
    card: {
      title: "state.table",
      fields: ["state.database", "state.kind", "state.status"],
    },
    ...overrides,
  };
}

const eq = (field: string, value: string | boolean): FilterNode => ({
  field,
  operator: "eq",
  value,
});

/**
 * The sources an approver has to approve before datasets read them: reviewed
 * (a draft awaits its review first) and not approved yet.
 */
export const AWAITING_APPROVAL: FilterNode[] = [
  { field: "state.status", operator: "in", value: ["REVIEWED", "ADOPTED"] },
  eq("state.approved", false),
];

/**
 * The system views: the sources a data owner works through. _Awaiting
 * approval_ only where the catalog takes an approver for a source
 * (`requireSourceApproval`): elsewhere none awaits one.
 */
export function sourceViews({ approval = true } = {}): SystemView[] {
  return [
    { id: "all", title: t.viewAll, config: sourcesView([]) },
    {
      id: "drafts",
      title: t.viewDrafts,
      config: sourcesView([eq("state.status", "DRAFT")]),
    },
    ...(approval
      ? [
          {
            id: "unapproved",
            title: t.viewUnapproved,
            config: sourcesView(AWAITING_APPROVAL),
          },
        ]
      : []),
    {
      id: "wow",
      title: t.viewWow,
      config: sourcesView([eq("state.kind", "WOW")]),
    },
    {
      id: "analyst",
      title: t.viewAnalyst,
      config: sourcesView([eq("state.kind", "ANALYST")]),
    },
    {
      id: "missing",
      title: t.viewMissing,
      config: sourcesView([eq("state.missing", true)]),
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
        metrics: [{ alias: "count", type: "count" }],
        sort: [{ alias: "count", direction: "desc" }],
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

const yesNo = (
  name: string,
  label: FieldDefinition["label"],
): FieldDefinition => ({
  name,
  label,
  kind: "boolean",
  sortable: true,
});

/**
 * The catalog's `source` snapshots as the engine reads them: one table or
 * view of the warehouse each (D241), an analyst's or a Wow one. Written by
 * hand: the paths are the generated `SourceStateField`s.
 */
export const sources: DataViewDefinition = {
  id: SOURCES,
  kind: "data",
  source: SOURCES_SOURCE,
  title: t.title,
  recordNoun: t.recordNoun,
  fields: [
    { ...string("aggregateId", t.id), cell: "copyable" },
    string("state.connection", t.connection),
    string("state.database", t.database),
    string("state.table", t.table),
    string("state.tableEngine", t.tableEngine),
    {
      name: "state.kind",
      label: t.kind,
      kind: "enum",
      sortable: true,
      options: [
        { value: "ANALYST", label: t.kindAnalyst },
        { value: "WOW", label: t.kindWow },
      ],
    },
    {
      name: "state.status",
      label: t.status,
      kind: "enum",
      sortable: true,
      cell: "status",
      options: [
        { value: "DRAFT", label: t.statusDraft, tone: "warning" },
        { value: "REVIEWED", label: t.statusReviewed, tone: "success" },
        { value: "ADOPTED", label: t.statusAdopted, tone: "success" },
      ],
    },
    yesNo("state.missing", t.missing),
    yesNo("state.platform", t.platform),
    yesNo("state.approved", t.approved),
    { name: "state.pending", label: t.pending, kind: "array" },
    string("state.tenantColumn", t.tenantColumn),
    string("state.ownerColumn", t.ownerColumn),
    string("state.spaceColumn", t.spaceColumn),
    string("state.enrichment.context", t.context),
    string("state.enrichment.aggregate", t.aggregate),
    string("state.enrichment.writer", t.writer),
  ],
  record: {
    rowKey: "aggregateId",
    paging: "paged",
    layouts: ["table", "card"],
    // What the actions read to decide what a source takes.
    rowFields: [
      "state.kind",
      "state.status",
      "state.missing",
      "state.approved",
      "state.pending",
      "state.platform",
      "state.tenantColumn",
      "state.ownerColumn",
      "state.spaceColumn",
    ],
  },
  analysis: {
    count: true,
    fields: ["state.status", "state.kind", "state.database"].map((field) => ({
      field,
      groups: [AggregationGroupType.TERMS],
      functions: [],
      expressionInput: false,
    })),
  },
  views: sourceViews(),
};

/**
 * The sources as the catalog `settings` has them: without an approver for a
 * source, no view of the sources awaiting one.
 */
export function sourcesOf(settings: {
  requireSourceApproval: boolean;
}): DataViewDefinition {
  return {
    ...sources,
    views: sourceViews({ approval: settings.requireSourceApproval }),
  };
}
