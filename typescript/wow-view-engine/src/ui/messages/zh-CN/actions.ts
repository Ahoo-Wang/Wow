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

import type { actionsMessages } from '../actions.js';

/**
 * `actionsMessages` 的简体中文，逐键对应（`../actions.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const actionsZhCN = {
  // 宿主声明的操作（host-integration.md 5）：引擎在命令周围说的话。命令本身
  // 的名字与拒绝理由是宿主的（`text(key)`）。
  'label.action.more': '{record} 的操作',
  'label.action.row': '{action} {record}',
  'label.action.bulk': '{action} {count} 条',
  'label.action.bulk-able': '{action} {able}/{count} 条',
  'label.action.confirm': '对 {count} 条记录执行「{action}」？',
  'label.action.confirm-record': '对 {record} 执行「{action}」？',
  'label.action.on-record': '{action}：{record}',
  'label.action.record': '记录 {record}',
  'label.action.left': '被拒绝的记录仍保持勾选，并写明原因。',
  'label.action.able': '{count} 条里 {able} 条能{action}。',
  'label.action.none-able': '{count} 条里没有一条现在能{action}。',
  'label.action.refused': '以下不会发送，仍保持勾选：',
  'label.action.refused-keys-more': '{keys} 等另外 {count} 条',
  'label.action.only-able': '只选能做的 {count} 条',
  'label.action.only-able-one': '只选能做的 1 条',
  'label.action.cancel': '取消',
  'label.action.required': '必填',
  'label.action.unseen': '不在当前这一页上',
  'label.action.not-offered': '这条记录不提供此操作',
  'label.action.unavailable': '现在不可用',
  'label.action.failed': '失败，未给出原因',
  'label.action.timed-out': '超时未答复',
  'label.action.abandoned': '已不再等待',
  // 开发构建里报给宿主研发、不给读者看：操作的条件读了行上没取回的字段。
  'record.action.unfetched':
    '操作「{action}」的条件读了 {field}，这个视图的行不取回这个字段。把它写进定义的 record.rowFields。',
} satisfies Record<keyof typeof actionsMessages, string>;
