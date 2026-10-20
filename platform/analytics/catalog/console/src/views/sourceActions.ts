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
import { idOf, said, stateOf } from "./actionSupport.ts";
import { textKeys, type Words } from "./words.ts";

/** The words of the actions on a source, per language. */
export const SOURCE_ACTION_WORDS = {
  en: {
    markReviewed: "Mark reviewed",
    markReviewedConfirm: "Mark {count} sources reviewed?",
    "markReviewedConfirm-one": "Mark {count} source reviewed?",
    markReviewedBody:
      "Datasets may then read it. Its tenant column (or that it is a platform dimension) is declared and its string columns reviewed.",
    approve: "Approve for datasets",
    approveConfirm: "Approve {count} sources for the datasets?",
    "approveConfirm-one": "Approve {count} source for the datasets?",
    approveBody:
      "Where the catalog takes an approver for new sources, datasets read only approved ones.",
    approveLower: "Approve lower protection",
    approveLowerConfirm: "Approve the lower protection of {count} sources?",
    "approveLowerConfirm-one":
      "Approve the lower protection of {count} source?",
    approveLowerBody:
      "The values its wow_catalog row now protects less take the row's protection.",
    wowTable: "A Wow table: its wow_catalog row describes it.",
    draft: "A draft: it is reviewed first.",
    notDraft: "Already reviewed.",
    alreadyApproved: "Already approved.",
    tableGone: "Its table is gone.",
    analystTable:
      "An analyst's table: no wow_catalog row lowers its protection.",
  },
  "zh-CN": {
    markReviewed: "标记已审查",
    markReviewedConfirm: "将 {count} 个数据源标记为已审查？",
    "markReviewedConfirm-one": "将 {count} 个数据源标记为已审查？",
    markReviewedBody:
      "此后数据集可读取它。须已声明租户列（或为平台维度），且字符串列均已审查。",
    approve: "审批供数据集使用",
    approveConfirm: "审批 {count} 个数据源供数据集使用？",
    "approveConfirm-one": "审批 {count} 个数据源供数据集使用？",
    approveBody: "目录要求审批新数据源时，数据集只读取已审批的数据源。",
    approveLower: "审批降低的保护",
    approveLowerConfirm: "审批 {count} 个数据源降低的保护？",
    "approveLowerConfirm-one": "审批 {count} 个数据源降低的保护？",
    approveLowerBody: "其 wow_catalog 行现在保护较低的值，采用该行的保护。",
    wowTable: "Wow 表：由其 wow_catalog 行描述。",
    draft: "草稿：须先审查。",
    notDraft: "已经审查。",
    alreadyApproved: "已经审批。",
    tableGone: "其表已不在。",
    analystTable: "分析师的表：没有 wow_catalog 行会降低其保护。",
  },
} satisfies Words;

const t = textKeys("sourceActions", SOURCE_ACTION_WORDS.en);

function analyst(row: RecordRow): true | string {
  return stateOf(row).kind === "WOW" ? t.wowTable : true;
}

/**
 * A data owner's actions on a source (D241, D251 ②): an approver marks an
 * analyst's table reviewed, approves a source
 * for the datasets, and accepts the lower protection a Wow table's row now
 * gives. The service keeps the last word: a role the action needs, a source
 * that is not complete, are its refusals, said in its words.
 */
export function sourceActions(commands: CatalogCommands): RecordActions {
  return actions([
    {
      id: "markReviewed",
      label: t.markReviewed,
      primary: true,
      available: (row) => {
        const kind = analyst(row);
        if (kind !== true) return kind;
        if (stateOf(row).missing === true) return t.tableGone;
        return stateOf(row).status === "DRAFT" ? true : t.notDraft;
      },
      confirm: { title: t.markReviewedConfirm, body: t.markReviewedBody },
      run: (row) => said(commands.markReviewed(idOf(row))),
    },
    {
      id: "approve",
      label: t.approve,
      available: (row) => {
        if (stateOf(row).status === "DRAFT") return t.draft;
        return stateOf(row).approved === true ? t.alreadyApproved : true;
      },
      confirm: { title: t.approveConfirm, body: t.approveBody },
      run: (row) => said(commands.approveSource(idOf(row))),
    },
    {
      id: "approveLower",
      label: t.approveLower,
      available: (row) => (stateOf(row).kind === "WOW" ? true : t.analystTable),
      confirm: {
        title: t.approveLowerConfirm,
        body: t.approveLowerBody,
        tone: "danger",
      },
      run: (row) => said(commands.approveSensitivityChange(idOf(row))),
    },
  ]);
}
