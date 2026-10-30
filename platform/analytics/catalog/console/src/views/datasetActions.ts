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
  actions,
  type RecordActions,
  type RecordRow,
} from "@ahoo-wang/wow-view-engine";
import type { CatalogSettings } from "@/generated/index.ts";
import { ADMIN_ROLE, APPROVER_ROLE, EDITOR_ROLE } from "@/services/caller.ts";
import type { CatalogCommands } from "./catalogCommands.ts";
import {
  firstRefusal,
  holds,
  idOf,
  said,
  stateOf,
  textOf,
  UNKNOWN_ROLES,
  type CallerRoles,
} from "./actionSupport.ts";
import { textKeys, type Words } from "./words.ts";

/** The words of the actions on a dataset, per language. */
export const DATASET_ACTION_WORDS = {
  en: {
    publish: "Publish",
    publishConfirm: "Publish {count} datasets?",
    "publishConfirm-one": "Publish {count} dataset?",
    publishBody:
      "Each is compiled against the catalog as it is now into its next version's view, which queries then read; one that takes an approver awaits one.",
    publishBodyApproval:
      "Every publication of this catalog takes an approver: each awaits one, in Awaiting approval, before queries read it.",
    publishDone:
      "{count} published, or awaiting an approver where one is needed",
    "publishDone-one":
      "{record} published, or awaiting an approver if it needs one",
    publishRequested: "{count} awaiting an approver",
    "publishRequested-one": "{record} awaiting an approver",
    approve: "Approve publication",
    approveConfirm: "Approve the publication of {count} datasets?",
    "approveConfirm-one": "Approve the publication of {count} dataset?",
    approveBody: "The requested definition is compiled and published.",
    refuse: "Refuse publication",
    refuseConfirm: "Refuse the publication of {count} datasets?",
    "refuseConfirm-one": "Refuse the publication of {count} dataset?",
    reason: "Reason",
    rollback: "Roll back",
    rollbackConfirm: "Switch {count} datasets back?",
    "rollbackConfirm-one": "Switch {count} dataset back?",
    rollbackBody:
      "Queries read the version given again; its view is created again if it is gone. The detail lists the versions published.",
    version: "Version",
    alreadyServing: "That version is the one serving.",
    neverPublished:
      "That version was never published: the detail lists those that were.",
    onlyVersion: "Only the version serving was published.",
    retire: "Retire",
    retireConfirm: "Retire {count} datasets?",
    "retireConfirm-one": "Retire {count} dataset?",
    retireBody:
      "Its queries stop; its views stay for the retention (7 days by default), then they are dropped. Publishing it again before then brings it back.",
    retired: "Retired.",
    notPublished: "Never published.",
    nothingRequested: "No publication awaits approval.",
    editorsOnly:
      "Takes the role analytics.catalog.editor or analytics.catalog.admin.",
    approversOnly: "Takes the role analytics.catalog.approver.",
  },
  "zh-CN": {
    publish: "发布",
    publishConfirm: "发布 {count} 个数据集？",
    "publishConfirm-one": "发布 {count} 个数据集？",
    publishBody:
      "每个都按当前目录编译为下一版本的视图，此后查询读取它；需要审批的会等待审批。",
    publishBodyApproval:
      "此目录的每次发布都需要审批：每个都在「待审批」中等待审批，批准后查询才读取它。",
    publishDone: "{count} 个已发布，需要审批的等待审批",
    "publishDone-one": "{record} 已发布，如需审批则等待审批",
    publishRequested: "{count} 个等待审批",
    "publishRequested-one": "{record} 等待审批",
    approve: "批准发布",
    approveConfirm: "批准 {count} 个数据集的发布？",
    "approveConfirm-one": "批准 {count} 个数据集的发布？",
    approveBody: "请求发布的定义将被编译并发布。",
    refuse: "拒绝发布",
    refuseConfirm: "拒绝 {count} 个数据集的发布？",
    "refuseConfirm-one": "拒绝 {count} 个数据集的发布？",
    reason: "原因",
    rollback: "回滚",
    rollbackConfirm: "将 {count} 个数据集切回？",
    "rollbackConfirm-one": "将 {count} 个数据集切回？",
    rollbackBody:
      "查询再次读取所给版本；其视图如已不在会重新创建。已发布的版本列在详情中。",
    version: "版本",
    alreadyServing: "该版本正在提供。",
    neverPublished: "该版本从未发布：详情中列出了已发布的版本。",
    onlyVersion: "只发布过正在提供的版本。",
    retire: "下线",
    retireConfirm: "下线 {count} 个数据集？",
    "retireConfirm-one": "下线 {count} 个数据集？",
    retireBody:
      "其查询停止；视图保留一段时间（默认 7 天）后删除。在此之前再次发布可恢复。",
    retired: "已下线。",
    notPublished: "从未发布。",
    nothingRequested: "没有等待审批的发布。",
    editorsOnly:
      "需要 analytics.catalog.editor 或 analytics.catalog.admin 角色。",
    approversOnly: "需要 analytics.catalog.approver 角色。",
  },
} satisfies Words;

const t = textKeys("datasetActions", DATASET_ACTION_WORDS.en);

function published(row: RecordRow): true | string {
  const status = stateOf(row).status;
  if (status === "RETIRED") return t.retired;
  return status === "DRAFT" ? t.notPublished : true;
}

/** Whether a publication awaits an approver: its reasons are kept until it is decided. */
function requested(row: RecordRow): true | string {
  const reasons = stateOf(row).requestReasons;
  return Array.isArray(reasons) && reasons.length > 0
    ? true
    : t.nothingRequested;
}

/**
 * Whether the dataset can be switched back to the version typed: not the
 * one a published dataset serves (a dataset that needs revision gets its
 * view created again), and, where the row holds the versions (the detail
 * reads the whole record), one that was published.
 */
function rollsBackTo(row: RecordRow, version: unknown): true | string {
  const state = stateOf(row);
  const serving = state.status === "PUBLISHED";
  const versions = Array.isArray(state.versions)
    ? (state.versions as { artifact?: { version?: unknown } }[]).map(
        (each) => each.artifact?.version,
      )
    : null;
  if (serving && versions !== null && versions.length <= 1)
    return t.onlyVersion;
  if (version === undefined || version === null || version === "") return true;
  const wanted = Number(version);
  if (serving && wanted === state.current) return t.alreadyServing;
  return versions === null || versions.includes(wanted)
    ? true
    : t.neverPublished;
}

/** Who and what decides what a data owner's actions offer. */
export interface DatasetActionOptions {
  /** The caller's roles where they decide; the service alone says otherwise. */
  roles?: CallerRoles;
  /** The catalog's settings once read: whether every publication takes an approver. */
  settings?: CatalogSettings | null;
}

/**
 * A data owner's actions on a dataset (D251 ②, D259): an analyst publishes,
 * rolls back and retires their wide tables, an approver decides a
 * publication that takes one. What the caller's roles or the dataset rule
 * out is offered disabled, with why; the service keeps the last word: a
 * definition that no longer compiles is its refusal, said in its words.
 * A publication that takes an approver is only requested: the outcome says
 * so, and the dataset waits in _Awaiting approval_.
 */
export function datasetActions(
  commands: CatalogCommands,
  { roles = UNKNOWN_ROLES, settings = null }: DatasetActionOptions = {},
): RecordActions {
  const editor = () => holds(roles, [EDITOR_ROLE, ADMIN_ROLE], t.editorsOnly);
  const approver = () => holds(roles, [APPROVER_ROLE], t.approversOnly);
  const everyApproved = settings?.requirePublicationApproval === true;
  return actions([
    {
      id: "publish",
      label: t.publish,
      primary: true,
      available: () => editor(),
      confirm: {
        title: t.publishConfirm,
        body: everyApproved ? t.publishBodyApproval : t.publishBody,
      },
      done: everyApproved ? t.publishRequested : t.publishDone,
      run: (row) => said(commands.publish(idOf(row))),
    },
    {
      id: "approve",
      label: t.approve,
      available: (row) => firstRefusal(approver, () => requested(row)),
      confirm: { title: t.approveConfirm, body: t.approveBody },
      run: (row) => said(commands.approvePublication(idOf(row))),
    },
    {
      id: "refuse",
      label: t.refuse,
      tone: "danger",
      available: (row) => firstRefusal(approver, () => requested(row)),
      form: { reason: { label: t.reason } },
      confirm: { title: t.refuseConfirm },
      run: (row, input) =>
        said(
          commands.refusePublication(idOf(row), textOf(input, "reason") ?? ""),
        ),
    },
    {
      id: "rollback",
      label: t.rollback,
      available: (row, { input }) =>
        firstRefusal(
          editor,
          () => published(row),
          () => rollsBackTo(row, input?.version),
        ),
      form: { version: { label: t.version, input: "number" } },
      confirm: { title: t.rollbackConfirm, body: t.rollbackBody },
      run: (row, input) =>
        said(commands.rollback(idOf(row), Number(input.version))),
    },
    {
      id: "retire",
      label: t.retire,
      tone: "danger",
      available: (row) => firstRefusal(editor, () => published(row)),
      confirm: { title: t.retireConfirm, body: t.retireBody },
      run: (row) => said(commands.retire(idOf(row))),
    },
  ]);
}
