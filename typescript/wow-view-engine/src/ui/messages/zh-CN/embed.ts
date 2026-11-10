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

import type { embedMessages } from '../embed.js';

/**
 * `embedMessages` 的简体中文，逐键对应（`../embed.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const embedZhCN = {
  // 嵌入（D22 嵌入一半）：页面锁定的筛选。
  'label.embed.locked': '由页面设定',
  'label.embed.unavailable-removed':
    '数据源不再支持的条件在这里已去掉；保存的视图没有改。',
  'label.embed.locked-name': '{filter}（由页面设定）',
  'label.embed.any': '不限',
} satisfies Record<keyof typeof embedMessages, string>;
