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
  emptyDashboardConfig,
  inRows,
  systemInstanceId,
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DashboardViewConfig,
  type FilterNode,
  type PanelSize,
  type SizedPanel,
  type Text,
} from "@ahoo-wang/wow-view-engine";
import {
  ACTIVE,
  ATTENTION,
  analysisView,
  clusters,
  PARKED,
  REFRESH_SECONDS,
  status,
} from "./parked.ts";
import { textKeys, type Words } from "./words.ts";

/** The overview's definition: a board owns no data, only its system board. */
export const OVERVIEW = "ops-overview";

/** The console's home page: the overview's one system board. */
export const OVERVIEW_BOARD = systemInstanceId(OVERVIEW, "home");

/** The board's one filter: the window its panels count in. */
export const OVERVIEW_WINDOW = "window";

export const OVERVIEW_WORDS = {
  en: {
    title: "Overview",
    board: "Parked entries overview",
    window: "Failed within",
    inWindow: "Parked, failed in range",
    allParked: "All parked",
    attention: "Needs attention",
    retrying: "Retrying",
    unrecoverable: "Unrecoverable",
    resolved: "Resolved, failed in range",
    overTime: "Parked over time, by status",
    byStatus: "By status",
    clusters: "Failure clusters — top 10",
    byContext: "By context",
    bySubscription: "Top failing subscriptions",
    attentionList: "Needing attention",
  },
  "zh-CN": {
    title: "概览",
    board: "搁置条目概览",
    window: "失败时间",
    inWindow: "搁置中（范围内失败）",
    allParked: "全部搁置中",
    attention: "需要处理",
    retrying: "重试中",
    unrecoverable: "不可恢复",
    resolved: "已解决（范围内失败）",
    overTime: "搁置趋势（按状态）",
    byStatus: "按状态",
    clusters: "失败集群 · 前 10 个",
    byContext: "按上下文",
    bySubscription: "失败最多的订阅",
    attentionList: "最需要处理",
  },
} satisfies Words;

const t = textKeys("overview", OVERVIEW_WORDS.en);

/** The entries `filter` keeps, read as one number. */
function countCard(filter: FilterNode[]): AnalysisViewConfig {
  return analysisView(
    {
      groups: [],
      sort: [],
      limit: 1,
      layout: "chart",
      chart: { type: "metric", metric: { metric: "count" } },
    },
    filter,
  );
}

/** The parked entries by status, as a donut. */
function byStatus(): AnalysisViewConfig {
  return analysisView(
    {
      groups: [{ type: "TERMS", field: "state.status", alias: "status" }],
      limit: 4,
      layout: "chart",
      chart: {
        type: "pie",
        pie: { category: "status", value: "count", donut: true },
      },
    },
    [],
  );
}

/** A panel's size on the board's 24-column grid; `inRows` places it. */
const size = (w: number, h: number): PanelSize => ({ w, h });

/** A panel of a view the board owns over the parked entries. */
function owned(
  id: string,
  title: Text,
  config: AnalysisViewConfig,
  layout: PanelSize,
  extra: Partial<SizedPanel> = {},
): SizedPanel {
  return {
    id,
    kind: "view",
    title,
    bindings: [],
    layout,
    owned: { definitionId: PARKED, config },
    ...extra,
  } as SizedPanel;
}

/** A panel showing one of the parked entries' system views. */
function system(
  view: string,
  title: Text,
  layout: PanelSize,
  extra: Partial<SizedPanel> = {},
): SizedPanel {
  return {
    id: view,
    kind: "view",
    title,
    bindings: [],
    layout,
    instanceId: systemInstanceId(PARKED, view),
    ...extra,
  } as SizedPanel;
}

/**
 * The console's home page as a board, the 9.x compensation overview's
 * place: the pile in numbers, how it grew, what it fails with and where,
 * and the entries needing a person.
 *
 * - Its one filter is the window, the last 7 days until the reader picks
 *   another, which reaches each panel through when its entries failed
 *   (`state.failedAt`, the definition's time field). 「All parked」 reads
 *   the pile whole (`ignoresTime`), as the 9.x overview's backlog did.
 * - A number opens the queue it counts; a cluster opens its parked entries
 *   (the row's error type, context and subscription as the queue's
 *   conditions), under the board's window.
 * - It reads the entries again every 15 seconds, as the queues do.
 */
function homeBoard(): DashboardViewConfig {
  const card = (
    id: string,
    title: Text,
    filter: FilterNode[],
    opens: string,
    windowed = true,
  ) =>
    owned(id, title, countCard(filter), size(4, 2), {
      opens: systemInstanceId(PARKED, opens),
      ...(windowed ? {} : { ignoresTime: true as const }),
    });
  return {
    ...emptyDashboardConfig(),
    refresh: { interval: REFRESH_SECONDS },
    width: "full",
    fields: [
      {
        name: OVERVIEW_WINDOW,
        label: t.window,
        kind: "datetime",
        default: { type: "relative", amount: 7, unit: "day" },
        required: true,
      },
    ],
    panels: inRows(
      [
        card("in-window", t.inWindow, [ACTIVE], "active"),
        card("all-parked", t.allParked, [ACTIVE], "active", false),
        card("needs-attention", t.attention, [ATTENTION], "attention"),
        card("retrying", t.retrying, [status("SCHEDULED")], "scheduled"),
        card(
          "unrecoverable",
          t.unrecoverable,
          [status("UNRECOVERABLE")],
          "unrecoverable",
        ),
        card("resolved", t.resolved, [status("RESOLVED")], "resolved"),
      ],
      [
        system("over-time", t.overTime, size(16, 5)),
        owned("by-status", t.byStatus, byStatus(), size(8, 5)),
      ],
      [
        owned("clusters", t.clusters, clusters(10), size(24, 5), {
          // The 9.x overview's cluster link: the cluster's parked entries.
          click: {
            kind: "view",
            instanceId: systemInstanceId(PARKED, "active"),
          },
          // Every cluster, in the workbench.
          opens: systemInstanceId(PARKED, "clusters"),
        }),
      ],
      [
        system("by-context", t.byContext, size(12, 5)),
        system("by-subscription", t.bySubscription, size(12, 5)),
      ],
      [system("attention", t.attentionList, size(24, 7))],
    ),
  };
}

/** The overview's definition, with its one system board. */
export const overview: DashboardDefinition = {
  id: OVERVIEW,
  title: t.title,
  kind: "dashboard",
  views: [{ id: "home", title: t.board, config: homeBoard() }],
};
