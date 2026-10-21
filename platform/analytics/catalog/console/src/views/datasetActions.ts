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
import type { CatalogCommands } from "./catalogCommands.ts";
import { idOf, said, stateOf, textOf } from "./actionSupport.ts";
import { textKeys, type Words } from "./words.ts";

/** The words of the actions on a dataset, per language. */
export const DATASET_ACTION_WORDS = {
  en: {
    publish: "Publish",
    publishConfirm: "Publish {count} datasets?",
    "publishConfirm-one": "Publish {count} dataset?",
    publishBody:
      "Each is compiled against the catalog as it is now into its next version's view, which queries then read; one that takes an approver awaits one.",
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
      "Queries read the version given again; its view is created again if it is gone.",
    version: "Version",
    retire: "Retire",
    retireConfirm: "Retire {count} datasets?",
    "retireConfirm-one": "Retire {count} dataset?",
    retireBody:
      "Its queries stop; its views stay for the retention (7 days by default), then they are dropped. Publishing it again before then brings it back.",
    retired: "Retired.",
    notPublished: "Never published.",
  },
  "zh-CN": {
    publish: "发布",
    publishConfirm: "发布 {count} 个数据集？",
    "publishConfirm-one": "发布 {count} 个数据集？",
    publishBody:
      "每个都按当前目录编译为下一版本的视图，此后查询读取它；需要审批的会等待审批。",
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
    rollbackBody: "查询再次读取所给版本；其视图如已不在会重新创建。",
    version: "版本",
    retire: "下线",
    retireConfirm: "下线 {count} 个数据集？",
    "retireConfirm-one": "下线 {count} 个数据集？",
    retireBody:
      "其查询停止；视图保留一段时间（默认 7 天）后删除。在此之前再次发布可恢复。",
    retired: "已下线。",
    notPublished: "从未发布。",
  },
} satisfies Words;

const t = textKeys("datasetActions", DATASET_ACTION_WORDS.en);

function published(row: RecordRow): true | string {
  const status = stateOf(row).status;
  if (status === "RETIRED") return t.retired;
  return status === "DRAFT" ? t.notPublished : true;
}

/**
 * A data owner's actions on a dataset (D251 ②, D259): an analyst publishes,
 * rolls back and retires their wide tables, an approver decides a
 * publication that takes one. The service keeps the last word: a definition
 * that no longer compiles, a version never published, a role the action
 * needs, are its refusals, said in its words.
 */
export function datasetActions(commands: CatalogCommands): RecordActions {
  return actions([
    {
      id: "publish",
      label: t.publish,
      primary: true,
      confirm: { title: t.publishConfirm, body: t.publishBody },
      run: (row) => said(commands.publish(idOf(row))),
    },
    {
      id: "approve",
      label: t.approve,
      confirm: { title: t.approveConfirm, body: t.approveBody },
      run: (row) => said(commands.approvePublication(idOf(row))),
    },
    {
      id: "refuse",
      label: t.refuse,
      tone: "danger",
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
      available: published,
      form: { version: { label: t.version, input: "number" } },
      confirm: { title: t.rollbackConfirm, body: t.rollbackBody },
      run: (row, input) =>
        said(commands.rollback(idOf(row), Number(input.version))),
    },
    {
      id: "retire",
      label: t.retire,
      tone: "danger",
      available: published,
      confirm: { title: t.retireConfirm, body: t.retireBody },
      run: (row) => said(commands.retire(idOf(row))),
    },
  ]);
}
