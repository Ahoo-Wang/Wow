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

import type { bulkMessages } from '../bulk.js';

/**
 * `bulkMessages` 的简体中文，逐键对应（`../bulk.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const bulkZhCN = {
  // 宿主自己的批量命令做完之后那一条。量词跟着它作用的那份选择走
  // （`label.toolbar.selected` 是「已选 N 项」），说的就是那几项。
  'label.bulk.done': '{done} 项已完成',
  'label.bulk.failed': '{failed} 项失败',
  'label.bulk.unknown': '{unknown} 项结果未知，先刷新核对',
  'label.bulk.skipped': '{skipped} 项未执行',
  'label.bulk.one-done': '{record} 已完成',
  'label.bulk.one-failed': '{record} 失败：{reason}',
  'label.bulk.one-refused': '{record} 未执行：{reason}',
  'label.bulk.one-unknown': '{record} 结果未知，先刷新核对',
  'label.bulk.one-skipped': '{record} 未执行',
  'label.bulk.reason': '{reason}（{count} 项）',
  'label.bulk.more-reasons': '另有 {count} 种原因',
  'label.bulk.more-reasons-one': '另有 1 种原因',
  'label.bulk.left': '失败与未执行的仍选中',
  'label.bulk.running': '正在执行 {done}/{total}',
  'label.bulk.running-failed': '正在执行 {done}/{total}，{failed} 项失败',
  'label.bulk.stop': '停止',
  'label.bulk.stopping': '正在停止…',
  'label.bulk.stop-waiting': '不再等待',
  'label.bulk.dismiss': '知道了',
} satisfies Record<keyof typeof bulkMessages, string>;
